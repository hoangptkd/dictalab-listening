package com.dictalab.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Durable at-least-once delivery. A crash after SMTP acceptance can deliver a duplicate email. */
@Service
public class EmailOutbox {
    private final JdbcTemplate db;
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;

    public EmailOutbox(JdbcTemplate db, ObjectProvider<JavaMailSender> mail,
                       @Value("${dictalab.mail-from:}") String from) {
        this.db=db; this.mail=mail; this.from=from;
    }

    @Scheduled(initialDelayString="${dictalab.email.dispatch-delay-ms:1000}",
            fixedDelayString="${dictalab.email.dispatch-delay-ms:1000}")
    public void deliver() {
        // Never send an expired/consumed token; delete sensitive links after expiry.
        db.update("""
                UPDATE email_outbox SET status='FAILED',body='',lease_id=NULL,locked_until=NULL,last_error='TOKEN_EXPIRED'
                WHERE status IN ('PENDING','RUNNING') AND token_hash IN
                    (SELECT token_hash FROM account_tokens WHERE expires_at<=? OR used_at IS NOT NULL)
                """, ts(Instant.now()));
        if (mail.getIfAvailable()==null || from.isBlank()) return;
        db.update("""
                UPDATE email_outbox SET status='FAILED',body='',lease_id=NULL,locked_until=NULL,last_error='RETRY_EXHAUSTED'
                WHERE status='RUNNING' AND locked_until<? AND attempts>=5
                """, ts(Instant.now()));
        for (var job : db.queryForList("""
                SELECT id FROM email_outbox WHERE attempts<5 AND
                    ((status='PENDING' AND available_at<=?) OR (status='RUNNING' AND locked_until<?))
                ORDER BY available_at,id LIMIT 5
                """, ts(Instant.now()),ts(Instant.now()))) {
            String id=(String)job.get("id"), lease=UUID.randomUUID().toString();
            if(db.update("""
                    UPDATE email_outbox SET status='RUNNING',attempts=attempts+1,lease_id=?,locked_until=?
                    WHERE id=? AND attempts<5 AND ((status='PENDING' AND available_at<=?) OR (status='RUNNING' AND locked_until<?))
                    """,lease,ts(Instant.now().plusSeconds(90)),id,ts(Instant.now()),ts(Instant.now()))!=1) continue;
            var claims=db.queryForList("""
                    SELECT recipient,subject,body,attempts FROM email_outbox
                    WHERE id=? AND status='RUNNING' AND lease_id=? AND locked_until>?
                    """,id,lease,ts(Instant.now()));
            if(claims.isEmpty()) continue;
            var claimed=claims.get(0);
            try {
                SimpleMailMessage message=new SimpleMailMessage();
                message.setFrom(from); message.setTo((String)claimed.get("recipient"));
                message.setSubject((String)claimed.get("subject")); message.setText((String)claimed.get("body"));
                mail.getObject().send(message); // No database transaction or lock is held during SMTP.
                db.update("""
                        UPDATE email_outbox SET status='DELIVERED',delivered_at=?,body='',lease_id=NULL,locked_until=NULL,last_error=NULL
                        WHERE id=? AND status='RUNNING' AND lease_id=? AND locked_until>?
                        """,ts(Instant.now()),id,lease,ts(Instant.now()));
            } catch(RuntimeException failure) {
                int attempts=((Number)claimed.get("attempts")).intValue();
                boolean terminal=attempts>=5 || failure instanceof MailAuthenticationException
                        || failure instanceof MailParseException || failure instanceof MailPreparationException;
                // Equal jitter, 15..30s on the first failure, capped by the five-attempt limit.
                long ceiling=15L*(1L<<attempts);
                long delay=ThreadLocalRandom.current().nextLong(ceiling/2,ceiling+1);
                db.update("""
                        UPDATE email_outbox SET status=?,available_at=?,lease_id=NULL,locked_until=NULL,last_error=?,
                            body=CASE WHEN ? THEN '' ELSE body END
                        WHERE id=? AND status='RUNNING' AND lease_id=? AND locked_until>?
                        """,terminal?"FAILED":"PENDING",ts(Instant.now().plusSeconds(delay)),
                        failure.getClass().getSimpleName(),terminal,id,lease,ts(Instant.now()));
            }
        }
    }
    private static Timestamp ts(Instant instant){return Timestamp.from(instant);}
}
