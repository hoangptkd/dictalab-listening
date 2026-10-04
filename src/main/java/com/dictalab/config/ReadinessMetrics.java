package com.dictalab.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Small periodic DB snapshots, never a query per HTTP request or per metric label. */
@Component
public class ReadinessMetrics {
    private final JdbcTemplate db;
    private final MeterRegistry metrics;
    private final Path backupMarker;
    private final AtomicLong listeningPending=new AtomicLong(), listeningFailed=new AtomicLong(), listeningAge=new AtomicLong();
    private final AtomicLong mailPending=new AtomicLong(),mailFailed=new AtomicLong(),mailAge=new AtomicLong(),backupAge=new AtomicLong(-1);

    public ReadinessMetrics(JdbcTemplate db,MeterRegistry metrics,
            @Value("${dictalab.backup.success-marker:./backups/backup-success.json}") String backupMarker){
        this.db=db;this.metrics=metrics;this.backupMarker=Path.of(backupMarker);
        register("listening","pending",listeningPending);register("listening","failed",listeningFailed);
        register("email","pending",mailPending);register("email","failed",mailFailed);
        metrics.gauge("dictalab.jobs.oldest.seconds",List.of(Tag.of("type","listening")),listeningAge);
        metrics.gauge("dictalab.jobs.oldest.seconds",List.of(Tag.of("type","email")),mailAge);
        metrics.gauge("dictalab.backup.age.seconds",backupAge);
    }
    private void register(String type,String state,AtomicLong value){
        metrics.gauge("dictalab.jobs",List.of(Tag.of("type",type),Tag.of("state",state)),value);
    }
    @Scheduled(initialDelay=10000,fixedDelay=30000)
    public void refresh(){
        try{
            listeningPending.set(count("SELECT COUNT(*) FROM listening_passes WHERE grading_status IN ('PENDING','RUNNING')"));
            listeningFailed.set(count("SELECT COUNT(*) FROM listening_passes WHERE grading_status='FAILED'"));
            listeningAge.set(age("SELECT MIN(s.started_at) FROM listening_passes p JOIN listening_sessions s ON s.id=p.session_id WHERE p.grading_status IN ('PENDING','RUNNING')"));
            mailPending.set(count("SELECT COUNT(*) FROM email_outbox WHERE status IN ('PENDING','RUNNING')"));
            mailFailed.set(count("SELECT COUNT(*) FROM email_outbox WHERE status='FAILED'"));
            mailAge.set(age("SELECT MIN(created_at) FROM email_outbox WHERE status IN ('PENDING','RUNNING')"));
            backupAge.set(Files.isRegularFile(backupMarker)?Math.max(0,Instant.now().getEpochSecond()-Files.getLastModifiedTime(backupMarker).toInstant().getEpochSecond()):-1);
        }catch(Exception unavailable){metrics.counter("dictalab.metrics.refresh.failures").increment();}
    }
    private long count(String query){return db.queryForObject(query,Long.class);}
    private long age(String query){
        return db.query(query,rs->{if(!rs.next() || rs.getTimestamp(1)==null)return 0L;
            return Math.max(0,Instant.now().getEpochSecond()-rs.getTimestamp(1).toInstant().getEpochSecond());});
    }
}
