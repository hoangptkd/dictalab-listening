package com.dictalab.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import com.dictalab.config.PaymentOperationsProperties;
import java.time.*;
import static com.dictalab.service.PaymentData.*;

/** Transaction outbox deliberately separate from account-token email validation. */
@Service
public class PaymentNotifications {
    private final JdbcTemplate db;private final ObjectProvider<JavaMailSender> mail;private final String from;
    private final Clock clock;private final PaymentOperationsProperties config;
    public PaymentNotifications(JdbcTemplate db,ObjectProvider<JavaMailSender> mail,@Value("${dictalab.mail-from:}") String from,Clock paymentClock,PaymentOperationsProperties config) {
        this.db=db;this.mail=mail;this.from=from;this.clock=paymentClock;this.config=config;
    }
    @Scheduled(initialDelayString="${dictalab.payment-operations.dispatch-delay-ms:5000}",fixedDelayString="${dictalab.payment-operations.dispatch-delay-ms:5000}")
    public void dispatch() {
        if(!config.workerEnabled())return;
        db.update("UPDATE payment_outbox SET status='FAILED',lease_id=NULL,locked_until=NULL WHERE attempts>=5 AND status='RUNNING' AND locked_until<?",ts(clock.instant()));
        if(mail.getIfAvailable()==null || from.isBlank())return;
        for(var selected:rows(db,"SELECT id FROM payment_outbox WHERE attempts<5 AND ((status='PENDING' AND next_attempt<=?) OR (status='RUNNING' AND locked_until<?)) ORDER BY created_at,id LIMIT 5",ts(clock.instant()),ts(clock.instant()))) {
            String id=(String)selected.get("id"),lease=id();
            if(db.update("UPDATE payment_outbox SET status='RUNNING',attempts=attempts+1,lease_id=?,locked_until=? WHERE id=? AND attempts<5 AND ((status='PENDING' AND next_attempt<=?) OR (status='RUNNING' AND locked_until<?))",lease,ts(clock.instant().plusSeconds(90)),id,ts(clock.instant()),ts(clock.instant()))!=1)continue;
            var jobs=rows(db,"SELECT o.*,u.email FROM payment_outbox o LEFT JOIN app_users u ON u.id=o.user_id WHERE o.id=? AND o.lease_id=?",id,lease);
            if(jobs.isEmpty())continue;var job=jobs.get(0);
            if(job.get("email")==null) { db.update("UPDATE payment_outbox SET status='SKIPPED',lease_id=NULL,locked_until=NULL WHERE id=? AND lease_id=?",id,lease);continue; }
            try {
                var message=new SimpleMailMessage();message.setFrom(from);message.setTo((String)job.get("email"));
                message.setSubject("DictaLab · Xác nhận Premium");message.setText("Đơn "+job.get("order_code")+" đã được xác nhận. Xem thời hạn và chứng nhận thanh toán tại mục Tài khoản trên DictaLab. Đây là thông báo giao dịch, không phải hóa đơn thuế.");
                mail.getObject().send(message);
                db.update("UPDATE payment_outbox SET status='DELIVERED',delivered_at=?,lease_id=NULL,locked_until=NULL WHERE id=? AND lease_id=? AND locked_until>?",ts(clock.instant()),id,lease,ts(clock.instant()));
            } catch(RuntimeException failure) {
                int attempts=((Number)job.get("attempts")).intValue();
                db.update("UPDATE payment_outbox SET status=?,next_attempt=?,lease_id=NULL,locked_until=NULL WHERE id=? AND lease_id=?",attempts>=5?"FAILED":"PENDING",ts(clock.instant().plusSeconds(60L<<attempts)),id,lease);
            }
        }
    }
}
