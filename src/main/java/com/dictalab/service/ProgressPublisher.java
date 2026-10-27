package com.dictalab.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name="dictalab.progress.enabled", havingValue="true")
public class ProgressPublisher {
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;

    public ProgressPublisher(JdbcTemplate db, RabbitTemplate rabbit, MeterRegistry metrics) {
        this.db = db; this.rabbit = rabbit; this.metrics = metrics;
        metrics.gauge("dictalab.progress.outbox.pending", this,
                publisher -> publisher.pending);
        metrics.gauge("dictalab.progress.outbox.failed", this, publisher -> publisher.failed);
        metrics.gauge("dictalab.progress.outbox.oldest_seconds", this, publisher -> publisher.oldestSeconds);
    }
    private volatile int pending;
    private volatile int failed;
    private volatile double oldestSeconds;

    @Scheduled(initialDelayString="${dictalab.progress.dispatch-delay-ms:5000}",
            fixedDelayString="${dictalab.progress.dispatch-delay-ms:5000}")
    public void dispatch() {
        pending = db.queryForObject("SELECT COUNT(*) FROM progress_outbox WHERE status='PENDING'", Integer.class);
        failed = db.queryForObject("SELECT COUNT(*) FROM progress_outbox WHERE status='FAILED'", Integer.class);
        var oldest = db.queryForObject("SELECT MIN(created_at) FROM progress_outbox WHERE status='PENDING'", Timestamp.class);
        oldestSeconds = oldest == null ? 0 : Math.max(0, java.time.Duration.between(oldest.toInstant(), Instant.now()).toMillis()/1000.0);
        for (String id : db.queryForList("""
                SELECT id FROM progress_outbox WHERE status='PENDING' AND available_at<=CURRENT_TIMESTAMP
                    AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                ORDER BY available_at,id LIMIT 10
                """, String.class)) {
            String lease = UUID.randomUUID().toString();
            if (db.update("""
                    UPDATE progress_outbox SET lease_id=?,lease_until=?,attempts=attempts+1
                    WHERE id=? AND status='PENDING' AND available_at<=CURRENT_TIMESTAMP
                        AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                    """, lease, Timestamp.from(Instant.now().plusSeconds(30)), id) != 1) continue;
            var rows = db.queryForList("SELECT payload,attempts FROM progress_outbox WHERE id=? AND lease_id=?", id, lease);
            if (rows.isEmpty()) continue; // Concurrent account deletion scrubbed the body.
            var row = rows.get(0);
            var correlation = new CorrelationData(lease);
            try {
                var properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding("UTF-8");
                properties.setMessageId(id);
                properties.setHeader("X-Request-Id", id);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                rabbit.send("", ProgressOutbox.QUEUE,
                        new Message(((String) row.get("payload")).getBytes(StandardCharsets.UTF_8), properties), correlation);
                if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack() || correlation.getReturned() != null)
                    throw new IllegalStateException("Progress event publish not confirmed or routed");
                db.update("""
                        UPDATE progress_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP,lease_id=NULL,lease_until=NULL
                        WHERE id=? AND lease_id=?
                        """, id, lease);
                metrics.counter("dictalab.progress.publish", "outcome", "sent").increment();
            } catch (Exception failure) {
                int attempts = ((Number) row.get("attempts")).intValue();
                db.update("""
                        UPDATE progress_outbox SET status=?,available_at=?,lease_id=NULL,lease_until=NULL
                        WHERE id=? AND lease_id=?
                        """, attempts >= 20 ? "FAILED" : "PENDING",
                        Timestamp.from(Instant.now().plusSeconds(Math.min(300, 5L << Math.min(6, attempts)))), id, lease);
                metrics.counter("dictalab.progress.publish", "outcome", "failed").increment();
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return; // Broker outage does not burn through every pending event in one tick.
            }
        }
        db.update("DELETE FROM progress_outbox WHERE status='SENT' AND sent_at<?",
                Timestamp.from(Instant.now().minusSeconds(7 * 86400)));
    }

    public void retry(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid progress event ID");
        if (db.update("""
                UPDATE progress_outbox SET status='PENDING',attempts=0,available_at=CURRENT_TIMESTAMP,lease_id=NULL,lease_until=NULL
                WHERE id=? AND status='FAILED'
                """, id) != 1) throw new IllegalArgumentException("Only FAILED progress events can be retried");
    }
}
