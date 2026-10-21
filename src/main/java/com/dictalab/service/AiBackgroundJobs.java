package com.dictalab.service;

import com.dictalab.config.AiQueueProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Durable TTS/Coach outbox. Never blindly repeat a possibly billable call after a worker crash. */
@Service
@ConditionalOnProperty(name = "dictalab.ai.queue.enabled", havingValue = "true")
public class AiBackgroundJobs {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final RabbitTemplate rabbit;
    private final AiQueueProperties config;
    private final ObjectProvider<ConversationService> conversation;
    private final ObjectProvider<CoachService> coach;
    private final MeterRegistry metrics;
    private final String databaseNow;
    private volatile int pendingAudio, pendingCoach;

    public AiBackgroundJobs(JdbcTemplate db, PlatformTransactionManager manager, RabbitTemplate rabbit,
                            AiQueueProperties config, ObjectProvider<ConversationService> conversation,
                            ObjectProvider<CoachService> coach, MeterRegistry metrics) {
        this.db = db; this.transaction = new TransactionTemplate(manager); this.rabbit = rabbit;
        this.config = config; this.conversation = conversation; this.coach = coach; this.metrics = metrics;
        // CURRENT_TIMESTAMP is transaction-start time on PostgreSQL, unsafe after waiting for locks.
        this.databaseNow = db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName().equals("PostgreSQL") ? "clock_timestamp()" : "CURRENT_TIMESTAMP");
        // Shared DB budgets, not worker count multiplied by the number of app instances.
        db.update("UPDATE ai_job_limits SET worker_limit=? WHERE kind='AUDIO'", config.audioWorkers());
        db.update("UPDATE ai_job_limits SET worker_limit=? WHERE kind='COACH'", config.coachWorkers());
        for (String kind : new String[]{"AUDIO", "COACH"}) {
            metrics.gauge("dictalab.ai.jobs.pending", java.util.List.of(io.micrometer.core.instrument.Tag.of("kind", kind)),
                    this, jobs -> kind.equals("AUDIO") ? jobs.pendingAudio : jobs.pendingCoach);
        }
    }

    @Scheduled(initialDelayString="${dictalab.metrics.snapshot-delay-ms:30000}",
            fixedDelayString="${dictalab.metrics.snapshot-delay-ms:30000}")
    public void refreshMetrics() {
        var counts = db.queryForList("SELECT kind,COUNT(*) AS pending FROM ai_background_jobs WHERE status='PENDING' GROUP BY kind");
        int audio = 0, coach = 0;
        for (var row : counts) {
            if ("AUDIO".equals(row.get("kind"))) audio = ((Number) row.get("pending")).intValue();
            if ("COACH".equals(row.get("kind"))) coach = ((Number) row.get("pending")).intValue();
        }
        pendingAudio = audio; pendingCoach = coach;
    }

    public static String hash(String utterance) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utterance.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String audioKey(String id, int turn, String variant, String hash) {
        return "audio/" + id + "/" + turn + "/" + variant + "/" + hash;
    }

    public String audio(String id, int turn, String variant, String utterance, boolean retry) {
        String hash = hash(utterance);
        return enqueue("AUDIO", audioKey(id, turn, variant, hash), id, null, turn, variant, hash, retry);
    }

    public String audioStatus(String id, int turn, String variant, String utterance) {
        return status(audioKey(id, turn, variant, hash(utterance)));
    }

    public String coach(String id, boolean retry) {
        return enqueue("COACH", "coach/" + id, null, id, null, null, null, retry);
    }

    public String coachStatus(String id) { return status("coach/" + id); }

    private String status(String key) {
        var states = db.queryForList("SELECT status,attempts FROM ai_background_jobs WHERE job_key=?", key);
        if (states.isEmpty()) return "NOT_REQUESTED";
        String state = (String) states.get(0).get("status");
        if (java.util.Set.of("FAILED", "UNKNOWN").contains(state)
                && ((Number) states.get(0).get("attempts")).intValue() >= 3) return "EXHAUSTED";
        return state;
    }

    private String enqueue(String kind, String key, String conversationId, String practiceId,
                           Integer turn, String variant, String hash, boolean retry) {
        return transaction.execute(tx -> {
            db.queryForObject("SELECT worker_limit FROM ai_job_limits WHERE kind=? FOR UPDATE", Integer.class, kind);
            var existing = db.queryForList("SELECT id,status,attempts FROM ai_background_jobs WHERE job_key=?", key);
            if (!existing.isEmpty()) {
                var row = existing.get(0);
                String state = (String) row.get("status");
                if (!retry || !java.util.Set.of("FAILED", "UNKNOWN", "DONE").contains(state)) return state;
                if (!state.equals("DONE") && ((Number) row.get("attempts")).intValue() >= 3) return "EXHAUSTED";
            }
            if (db.queryForObject("SELECT COUNT(*) FROM ai_background_jobs WHERE kind=? AND status IN ('PENDING','RUNNING')",
                    Integer.class, kind) >= 1000) return "BUSY";
            String id = UUID.randomUUID().toString();
            if (existing.isEmpty()) {
                db.update("""
                        INSERT INTO ai_background_jobs(id,job_key,kind,conversation_id,practice_id,turn_number,variant,input_hash)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, id, key, kind, conversationId, practiceId, turn, variant, hash);
            } else {
                // Rotate the ID: delayed messages from a previous explicit retry cannot start the new attempt.
                db.update("""
                        UPDATE ai_background_jobs SET id=?,attempts=CASE WHEN status='DONE' THEN 0 ELSE attempts END,
                        status='PENDING',lease_id=NULL,locked_until=NULL,
                        publish_after=NULL,last_error=NULL WHERE job_key=?
                        """, id, key);
            }
            return "PENDING";
        });
    }

    @Scheduled(initialDelayString = "${dictalab.ai.queue.dispatch-delay-ms:1000}",
            fixedDelayString = "${dictalab.ai.queue.dispatch-delay-ms:1000}")
    public void dispatch() {
        db.update("""
                UPDATE ai_background_jobs SET status='UNKNOWN',last_error='OUTCOME_UNKNOWN'
                WHERE status='RUNNING' AND locked_until<CURRENT_TIMESTAMP
                """);
        for (var row : db.queryForList("""
                SELECT id,kind FROM ai_background_jobs WHERE status='PENDING'
                AND (publish_after IS NULL OR publish_after<CURRENT_TIMESTAMP)
                ORDER BY created_at,id LIMIT 10
                """)) {
            String id = (String) row.get("id"), kind = (String) row.get("kind");
            if (db.update("""
                    UPDATE ai_background_jobs SET publish_after=CURRENT_TIMESTAMP + INTERVAL '15' SECOND
                    WHERE id=? AND status='PENDING' AND (publish_after IS NULL OR publish_after<CURRENT_TIMESTAMP)
                    """, id) != 1) continue;
            var confirm = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbit.convertAndSend(config.exchange(), kind.equals("AUDIO") ? config.audioQueue() : config.coachQueue(), id,
                        message -> { message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT); return message; }, confirm);
                if (!confirm.getFuture().get(5, TimeUnit.SECONDS).ack() || confirm.getReturned() != null)
                    throw new IllegalStateException("Broker did not accept AI job");
                db.update("""
                        UPDATE ai_background_jobs SET publish_after=CURRENT_TIMESTAMP + INTERVAL '30' SECOND
                        WHERE id=? AND status='PENDING'
                        """, id);
                metrics.counter("dictalab.ai.queue.published", "kind", kind).increment();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception unavailable) {
                metrics.counter("dictalab.ai.queue.publish_failures", "kind", kind).increment();
                // Pending rows survive publisher failures/queue overflow; no local unbounded fallback.
                return;
            }
        }
    }

    @RabbitListener(queues = "${dictalab.ai.queue.audio-queue:dictalab.conversation.audio}",
            concurrency = "${dictalab.ai.queue.audio-workers:2}")
    public void consumeAudio(String id) { consume(id, "AUDIO"); }

    @RabbitListener(queues = "${dictalab.ai.queue.coach-queue:dictalab.coach.reports}",
            concurrency = "${dictalab.ai.queue.coach-workers:1}")
    public void consumeCoach(String id) { consume(id, "COACH"); }

    private void consume(String id, String kind) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new AmqpRejectAndDontRequeueException("Invalid AI job ID");
        }
        String lease = UUID.randomUUID().toString();
        var claimed = transaction.execute(tx -> {
            int limit = db.queryForObject("SELECT worker_limit FROM ai_job_limits WHERE kind=? FOR UPDATE", Integer.class, kind);
            // Include UNKNOWN leases until their deadline so a late worker is not overlapped by recovery.
            if (db.queryForObject("SELECT COUNT(*) FROM ai_background_jobs WHERE kind=? AND locked_until>CURRENT_TIMESTAMP",
                    Integer.class, kind) >= limit) return java.util.List.<java.util.Map<String, Object>>of();
            if (db.update("""
                    UPDATE ai_background_jobs SET status='RUNNING',attempts=attempts+1,lease_id=?,
                    locked_until=CURRENT_TIMESTAMP + INTERVAL '180' SECOND
                    WHERE id=? AND kind=? AND status='PENDING' AND attempts<3
                    """, lease, id, kind) != 1) return java.util.List.<java.util.Map<String, Object>>of();
            return db.queryForList("SELECT * FROM ai_background_jobs WHERE id=? AND lease_id=?", id, lease);
        });
        if (claimed.isEmpty()) return; // DB dispatcher will republish pending work when capacity is free.
        var job = claimed.get(0);
        Runnable resultWrite;
        try {
            if (kind.equals("AUDIO")) {
                resultWrite = conversation.getObject().generateQueuedAudio((String) job.get("conversation_id"),
                        ((Number) job.get("turn_number")).intValue(), (String) job.get("variant"), (String) job.get("input_hash"), id);
            } else {
                resultWrite = coach.getObject().generateQueuedReport((String) job.get("practice_id"), id);
            }
        } catch (RuntimeException failure) {
            if (failure instanceof ProviderGuard.Unavailable unavailable && unavailable.retrySafe()) {
                // No request reached the provider. Defer without spending a billable attempt or replaying old IDs.
                boolean deferred = withLease(job, id, lease, () -> db.update("""
                        UPDATE ai_background_jobs SET id=?,status='PENDING',attempts=attempts-1,lease_id=NULL,
                        locked_until=NULL,publish_after=?,last_error='PROVIDER_BUSY'
                        WHERE id=?
                        """, UUID.randomUUID().toString(), java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(unavailable.retryAfter())), id));
                if (deferred) metrics.counter("dictalab.ai.queue.deferred", "kind", kind).increment();
                return;
            }
            // No transcript, answer, response or secret in job errors. Explicit user retry only.
            resultWrite = null;
        }
        Runnable write = resultWrite;
        boolean success = write != null;
        boolean committed = withLease(job, id, lease, () -> {
            db.update("""
                UPDATE ai_background_jobs SET status=?,last_error=?,lease_id=NULL,locked_until=NULL
                WHERE id=?
                """, success ? "DONE" : "FAILED", success ? null : "GENERATION_FAILED", id);
            if (write != null) write.run(); // A write failure rolls back DONE; recovery will mark the uncertain attempt.
        });
        if (committed) metrics.counter("dictalab.ai.queue.completed", "kind", kind, "outcome", success ? "success" : "failed").increment();
        else metrics.counter("dictalab.ai.queue.stale", "kind", kind).increment();
        // AUTO ACK only after the final state is durable. Failed persistence never resets RUNNING to PENDING.
    }

    private boolean withLease(java.util.Map<String, Object> job, String id, String lease, Runnable write) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            // Parent -> job order matches cascaded deletion and keeps the input stable during persistence.
            boolean audio = "AUDIO".equals(job.get("kind"));
            String table = audio ? "ai_conversation_sessions" : "practice_sessions";
            Object parent = job.get(audio ? "conversation_id" : "practice_id");
            if (db.queryForList("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", String.class, parent).isEmpty()) return false;
            if (db.queryForList("SELECT id FROM ai_background_jobs WHERE id=? FOR UPDATE", String.class, id).isEmpty()) return false;
            if (!Boolean.TRUE.equals(db.queryForObject("SELECT (status='RUNNING' AND lease_id=? AND locked_until>"
                    + databaseNow + ") FROM ai_background_jobs WHERE id=?", Boolean.class, lease, id))) return false;
            write.run();
            return true;
        }));
    }
}
