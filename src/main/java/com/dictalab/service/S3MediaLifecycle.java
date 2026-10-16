package com.dictalab.service;

import com.dictalab.config.MediaStorageProperties;
import com.dictalab.exception.MediaStorageException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Durable S3 upload/cleanup intent. Reference writers must retain in their own DB transaction. */
@Service
public class S3MediaLifecycle {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final ObjectProvider<MediaStorageService> media;
    private final MeterRegistry metrics;
    private final String identity;
    private final int retentionDays;
    private final String databaseNow;
    private final boolean enabled;
    private final boolean cleanupEnabled;
    private volatile int pendingCleanup;

    public S3MediaLifecycle(JdbcTemplate db, PlatformTransactionManager manager, MediaStorageProperties properties,
                           ObjectProvider<MediaStorageService> media, MeterRegistry metrics,
                           @Value("${dictalab.media.s3.cleanup-retention-days:7}") int retentionDays,
                           @Value("${dictalab.media.s3.cleanup-enabled:false}") boolean cleanupEnabled) {
        if (retentionDays < 1 || retentionDays > 365) throw new IllegalArgumentException("S3 retention must be 1..365 days");
        this.db = db; this.transaction = new TransactionTemplate(manager); this.media = media; this.metrics = metrics;
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.identity = properties.s3().bucket() + "/" + properties.s3().prefix();
        this.retentionDays = retentionDays;
        this.enabled = properties.s3().enabled();
        this.cleanupEnabled = cleanupEnabled;
        this.databaseNow = db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName().equals("PostgreSQL") ? "clock_timestamp()" : "CURRENT_TIMESTAMP");
        metrics.gauge("dictalab.media.cleanup.pending", this, service -> service.pendingCleanup);
    }

    @Scheduled(initialDelayString="${dictalab.metrics.snapshot-delay-ms:30000}",
            fixedDelayString="${dictalab.metrics.snapshot-delay-ms:30000}")
    public void refreshMetrics() {
        if (enabled) pendingCleanup = db.queryForObject(
                "SELECT COUNT(*) FROM s3_media_lifecycle WHERE cleanup_after IS NOT NULL", Integer.class);
    }

    boolean managed(String url) { return url != null && url.matches("/(?:media|conversation-media)/s3/[0-9a-f-]{36}\\.(mp3|m4a|wav)"); }

    public void stage(String url) {
        transaction.executeWithoutResult(tx -> db.update("""
                INSERT INTO s3_media_lifecycle(audio_url,storage_identity,status,upload_until,cleanup_after)
                VALUES (?,?,'STAGED',CURRENT_TIMESTAMP + INTERVAL '10' MINUTE,
                    CURRENT_TIMESTAMP + INTERVAL '1' DAY * CAST(? AS INTEGER))
                """, url, identity, retentionDays));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void retain(String url) {
        if (!enabled || !managed(url)) return;
        registerLegacy(url);
        db.queryForMap("SELECT audio_url FROM s3_media_lifecycle WHERE audio_url=? FOR UPDATE", url);
        var row = db.queryForMap("SELECT *,upload_until>" + databaseNow + " AS upload_valid FROM s3_media_lifecycle WHERE audio_url=?", url);
        if (!identity.equals(row.get("storage_identity")) || "DELETING".equals(row.get("status"))
                || "DELETED".equals(row.get("status")) || "STAGED".equals(row.get("status")) && !Boolean.TRUE.equals(row.get("upload_valid")))
            throw new MediaStorageException("Audio không còn có thể được gắn vào bài. Vui lòng tải lên lại.");
        db.update("UPDATE s3_media_lifecycle SET status='LIVE',upload_until=NULL,cleanup_after=NULL WHERE audio_url=?", url);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String url) {
        if (!enabled || !managed(url)) return;
        registerLegacy(url);
        db.update("UPDATE s3_media_lifecycle SET cleanup_after=CURRENT_TIMESTAMP + INTERVAL '1' DAY * CAST(? AS INTEGER) WHERE audio_url=? AND status IN ('LIVE','STAGED')",
                retentionDays, url);
    }

    private void registerLegacy(String url) {
        db.update("INSERT INTO s3_media_lifecycle(audio_url,storage_identity,status) VALUES (?,?,'LIVE') ON CONFLICT DO NOTHING", url, identity);
    }

    @Scheduled(initialDelayString = "${dictalab.media.s3.cleanup-delay-ms:60000}",
            fixedDelayString = "${dictalab.media.s3.cleanup-delay-ms:60000}")
    public void cleanup() {
        if (!enabled) return;
        // Account cascades and retention delete DB rows; schedule their private objects durably.
        db.update("""
                UPDATE s3_media_lifecycle SET cleanup_after=CURRENT_TIMESTAMP + INTERVAL '1' DAY * CAST(? AS INTEGER)
                WHERE audio_url LIKE '/conversation-media/s3/%' AND status='LIVE' AND cleanup_after IS NULL
                AND NOT EXISTS (SELECT 1 FROM ai_conversation_audio a WHERE a.s3_audio_url=s3_media_lifecycle.audio_url)
                """, retentionDays);
        for (String url : db.queryForList("SELECT audio_url FROM s3_media_lifecycle WHERE cleanup_after<=" + databaseNow
                + " AND storage_identity=? AND status<>'DELETED'"
                + (cleanupEnabled ? "" : " AND audio_url LIKE '/conversation-media/s3/%'")
                + " ORDER BY cleanup_after,audio_url LIMIT 10", String.class, identity)) {
            String lease = UUID.randomUUID().toString();
            boolean claimed = Boolean.TRUE.equals(transaction.execute(tx -> {
                var rows = db.queryForList("SELECT status,cleanup_after<=" + databaseNow + " AS due FROM s3_media_lifecycle WHERE audio_url=? FOR UPDATE", url);
                if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("due")) || "DELETED".equals(rows.get(0).get("status"))) return false;
                // All new references hold this catalog row until commit; no S3 call spans this lock.
                if (db.queryForObject("SELECT (SELECT COUNT(*) FROM lesson_sentences WHERE audio_url=?) + (SELECT COUNT(*) FROM listening_units WHERE audio_url=?) + (SELECT COUNT(*) FROM ai_conversation_audio WHERE s3_audio_url=?)",
                        Integer.class, url, url, url) > 0) {
                    db.update("UPDATE s3_media_lifecycle SET status='LIVE',cleanup_after=NULL WHERE audio_url=?", url);
                    return false;
                }
                db.update("UPDATE s3_media_lifecycle SET status='DELETING',lease_id=?,cleanup_after=" + databaseNow
                        + " + INTERVAL '120' SECOND,attempts=LEAST(attempts+1,20) WHERE audio_url=?", lease, url);
                return true;
            }));
            if (!claimed) continue;
            try {
                // UUID keys are immutable; an uncertain delete can safely be repeated, never a PUT.
                media.getObject().deleteIfManaged(url);
                db.update("UPDATE s3_media_lifecycle SET status='DELETED',cleanup_after=NULL,lease_id=NULL,last_error=NULL WHERE audio_url=? AND status='DELETING' AND lease_id=?", url, lease);
                metrics.counter("dictalab.media.cleanup.completed").increment();
            } catch (RuntimeException failure) {
                db.update("UPDATE s3_media_lifecycle SET lease_id=NULL,last_error='DELETE_FAILED',cleanup_after=" + databaseNow
                        + " + INTERVAL '60' SECOND * LEAST(60,POWER(2,LEAST(attempts,6))) WHERE audio_url=? AND status='DELETING' AND lease_id=?", url, lease);
                metrics.counter("dictalab.media.cleanup.failed").increment();
            }
        }
    }
}
