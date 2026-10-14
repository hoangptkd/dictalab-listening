package com.dictalab.service;

import com.dictalab.config.LearningRabbitConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Deterministic, retryable work only. No AI provider or network call inside the result transaction. */
@Service
@ConditionalOnProperty(name = "dictalab.learning.queue-enabled", havingValue = "true")
public class LearningJobs {
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final ListeningLearning learning;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final String databaseNow;

    public LearningJobs(JdbcTemplate db, RabbitTemplate rabbit, ListeningLearning learning,
                        PlatformTransactionManager manager, MeterRegistry metrics) {
        this.db = db; this.rabbit = rabbit; this.learning = learning; this.metrics = metrics;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(20);
        this.databaseNow = db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c ->
                "PostgreSQL".equals(c.getMetaData().getDatabaseProductName()) ? "clock_timestamp()" : "CURRENT_TIMESTAMP");
        for (String state : new String[]{"PENDING","RUNNING","FAILED"}) {
            var value=new java.util.concurrent.atomic.AtomicInteger();backlog.put(state,value);
            metrics.gauge("dictalab.learning.backlog", java.util.List.of(io.micrometer.core.instrument.Tag.of("state",state)), value);
        }
    }

    @Scheduled(initialDelayString="${dictalab.learning.dispatch-delay-ms:5000}",
            fixedDelayString="${dictalab.learning.dispatch-delay-ms:5000}")
    public void dispatch() {
        db.update("UPDATE learning_jobs SET status=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END, lease_id=NULL, locked_until=NULL WHERE status='RUNNING' AND locked_until<CURRENT_TIMESTAMP");
        for (String id : db.queryForList("SELECT id FROM learning_jobs WHERE status='PENDING' AND available_at<=CURRENT_TIMESTAMP AND publish_after<CURRENT_TIMESTAMP ORDER BY publish_after,id LIMIT 5", String.class)) {
            if (db.update("UPDATE learning_jobs SET publish_after=? WHERE id=? AND status='PENDING' AND publish_after<CURRENT_TIMESTAMP",
                    Timestamp.from(Instant.now().plusSeconds(30)), id) != 1) continue;
            var confirm = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbit.convertAndSend("", LearningRabbitConfig.QUEUE, id, m -> {
                    m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT); return m;
                }, confirm);
                if (!confirm.getFuture().get(5, TimeUnit.SECONDS).ack() || confirm.getReturned() != null)
                    throw new IllegalStateException("Learning publish not confirmed");
                metrics.counter("dictalab.learning.published").increment();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception failure) {
                metrics.counter("dictalab.learning.publish_failures").increment(); return;
            }
        }
    }

    @RabbitListener(queues=LearningRabbitConfig.QUEUE, concurrency="1")
    public void consume(String id) {
        try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { throw new AmqpRejectAndDontRequeueException("Invalid learning job ID"); }
        String lease = UUID.randomUUID().toString();
        int claimed = tx.execute(status -> db.update("UPDATE learning_jobs SET status='RUNNING',lease_id=?,locked_until=" + databaseNow + " + INTERVAL '60' SECOND,attempts=attempts+1 WHERE id=? AND status='PENDING' AND attempts<5 AND available_at<=" + databaseNow,
                lease, id));
        if (claimed == 0) return;
        try {
            boolean committed = Boolean.TRUE.equals(tx.execute(status -> {
                var parents = db.queryForList("SELECT s.user_id,s.id AS session_id FROM learning_jobs j JOIN sentence_attempts a ON a.id=j.attempt_id JOIN practice_sessions s ON s.id=a.practice_session_id WHERE j.id=?", id);
                if (parents.isEmpty()) return false;
                var parent = parents.get(0);
                if (db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, parent.get("user_id")).isEmpty()) return false;
                if (db.queryForList("SELECT id FROM practice_sessions WHERE id=? FOR UPDATE", String.class, parent.get("session_id")).isEmpty()) return false;
                var jobs = db.queryForList("SELECT attempt_id FROM learning_jobs WHERE id=? AND status='RUNNING' AND lease_id=? AND locked_until>" + databaseNow + " FOR UPDATE", id, lease);
                if (jobs.isEmpty()) return false;
                learning.collect(((Number) jobs.get(0).get("attempt_id")).longValue());
                if (db.update("UPDATE learning_jobs SET status='DONE',lease_id=NULL,locked_until=NULL WHERE id=? AND lease_id=? AND locked_until>" + databaseNow, id, lease) != 1) {
                    status.setRollbackOnly(); return false;
                }
                return true;
            }));
            metrics.counter("dictalab.learning.completed", "outcome", committed ? "done" : "stale").increment();
        } catch (RuntimeException failure) {
            // All projections rolled back. Retry deterministic work with bounded exponential delay and jitter.
            var remaining=db.queryForList("SELECT attempts FROM learning_jobs WHERE id=?", Integer.class, id);
            if (remaining.isEmpty()) return; // Account/session removal also removes its job.
            int attempts=remaining.get(0);
            int seconds = Math.min(300, 5 << Math.min(5, attempts)) + ThreadLocalRandom.current().nextInt(5);
            var available=Timestamp.from(Instant.now().plusSeconds(seconds));
            db.update("UPDATE learning_jobs SET status=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,lease_id=NULL,locked_until=NULL,publish_after=?,available_at=? WHERE id=? AND lease_id=?",
                    available, available, id, lease);
            metrics.counter("dictalab.learning.failures").increment();
            if (attempts >= 5) throw new AmqpRejectAndDontRequeueException("Learning job exhausted");
        }
        // Spring AUTO acknowledgement happens after durable result or durable retry state, not on receipt.
    }

    @Scheduled(initialDelayString="${dictalab.metrics.snapshot-delay-ms:30000}", fixedDelayString="${dictalab.metrics.snapshot-delay-ms:30000}")
    public void backlogMetrics() {
        for (String state : new String[]{"PENDING","RUNNING","FAILED"}) {
            backlog.get(state).set(db.queryForObject("SELECT COUNT(*) FROM learning_jobs WHERE status=?", Integer.class, state));
        }
    }
    public void retryFailed(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Invalid learning job ID");
        if (db.update("UPDATE learning_jobs SET status='PENDING',attempts=0,lease_id=NULL,locked_until=NULL,publish_after=CURRENT_TIMESTAMP,available_at=CURRENT_TIMESTAMP WHERE id=? AND status='FAILED'",id)!=1)
            throw new com.dictalab.exception.InvalidPracticeStateException("Chỉ phục hồi tác vụ FAILED sau khi xử lý nguyên nhân.");
    }
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> backlog = new java.util.concurrent.ConcurrentHashMap<>();
}
