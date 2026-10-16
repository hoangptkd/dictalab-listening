package com.dictalab.service;

import com.dictalab.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.dictalab.service.PaymentData.*;

@Service
public class PaymentWorker {
    private final JdbcTemplate db;
    private final PaymentOperations operations;
    private final PayOsClient payos;
    private final BillingProperties billing;
    private final PaymentOperationsProperties config;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    public PaymentWorker(JdbcTemplate db,PaymentOperations operations,PayOsClient payos,BillingProperties billing,
            PaymentOperationsProperties config,Clock paymentClock,PlatformTransactionManager manager,MeterRegistry metrics) {
        this.db=db;this.operations=operations;this.payos=payos;this.billing=billing;this.config=config;this.clock=paymentClock;
        this.tx=new TransactionTemplate(manager);tx.setTimeout(10);this.metrics=metrics;
        metrics.gauge("dictalab.payment.inbox.pending",this,w->w.count("SELECT COUNT(*) FROM payment_inbox WHERE status<>'PROCESSED'"));
        metrics.gauge("dictalab.payment.cases.overdue",this,w->w.db.queryForObject("SELECT COUNT(*) FROM reconciliation_cases WHERE status IN ('OPEN','INVESTIGATING') AND due_at<?",Long.class,ts(w.clock.instant())));
        metrics.gauge("dictalab.payment.refunds.unknown",this,w->w.count("SELECT COUNT(*) FROM refund_requests WHERE status='UNKNOWN'"));
        metrics.gauge("dictalab.payment.inbox.oldest.seconds",this,w->{
            var oldest=rows(w.db,"SELECT MIN(e.recorded_at) AS oldest FROM payment_events e JOIN payment_inbox i ON i.event_id=e.id WHERE i.status IN ('RECEIVED','RETRY','PROCESSING')");
            return oldest.get(0).get("oldest")==null?0:Math.max(0,Duration.between(at(oldest.get(0).get("oldest")),w.clock.instant()).getSeconds());
        });
        metrics.gauge("dictalab.payment.journal.imbalances",this,w->w.count("SELECT COUNT(*) FROM (SELECT j.id FROM ledger_journals j LEFT JOIN ledger_entries e ON e.journal_id=j.id WHERE j.status='POSTED' GROUP BY j.id HAVING SUM(e.debit_vnd)<>SUM(e.credit_vnd) OR COUNT(e.id)<2) bad"));
    }
    private double count(String sql) { try { return db.queryForObject(sql,Long.class); } catch(RuntimeException unavailable) { return Double.NaN; } }
    @Scheduled(initialDelayString="${dictalab.payment-operations.dispatch-delay-ms:5000}",fixedDelayString="${dictalab.payment-operations.dispatch-delay-ms:5000}")
    public void tick() {
        if(!billing.enabled() || !config.workerEnabled()) return;
        operations.backfill();
        for(var expired:rows(db,"SELECT event_id FROM payment_inbox WHERE status='PROCESSING' AND locked_until<? AND attempts>=8 ORDER BY event_id LIMIT 20",ts(clock.instant())))tx.executeWithoutResult(s->{
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            if(db.update("UPDATE payment_inbox SET status='QUARANTINED',last_error='RETRY_EXHAUSTED',lease_id=NULL,locked_until=NULL WHERE event_id=? AND status='PROCESSING' AND locked_until<? AND attempts>=8",expired.get("event_id"),ts(clock.instant()))==1)
                operations.caseFor("inbox:"+expired.get("event_id"),null,"RETRY_EXHAUSTED","Expired final processing lease; event="+expired.get("event_id"));
        });
        for(var row:rows(db,"SELECT event_id FROM payment_inbox WHERE attempts<8 AND ((status IN ('RECEIVED','RETRY') AND next_attempt<=?) OR (status='PROCESSING' AND locked_until<?)) ORDER BY next_attempt,event_id LIMIT 20",ts(clock.instant()),ts(clock.instant())))
            operations.process((String)row.get("event_id"));
    }
    @Scheduled(initialDelayString="${dictalab.payment-operations.reconcile-delay-ms:60000}",fixedDelayString="${dictalab.payment-operations.reconcile-delay-ms:60000}")
    public void reconcileDue() {
        if(!billing.enabled() || !config.reconcileEnabled() || !config.workerEnabled()) return;
        for(var row:rows(db,"SELECT order_code FROM payment_orders WHERE next_check_at<=? AND (check_until IS NULL OR check_until<?) ORDER BY next_check_at,order_code LIMIT 20",ts(clock.instant()),ts(clock.instant())))
            reconcile(number(row,"order_code"),"SCHEDULED");
    }
    public String request(long code,long actor,String reason) {
        text(reason,1000);
        return tx.execute(s->{
            if(operations.lockOrder(code).isEmpty())throw error(org.springframework.http.HttpStatus.NOT_FOUND,"PAYMENT_RESOURCE_NOT_FOUND");
            var queued=rows(db,"SELECT id FROM reconciliation_runs WHERE order_code=? AND status='QUEUED' ORDER BY created_at,id LIMIT 1",code);
            if(!queued.isEmpty())return (String)queued.get(0).get("id");
            db.update("UPDATE payment_orders SET next_check_at=? WHERE order_code=?",ts(clock.instant()),code);
            operations.audit(actor,"RECONCILIATION_REQUESTED",Long.toString(code),reason);
            String run=id();db.update("INSERT INTO reconciliation_runs(id,order_code,source,status,created_at) VALUES (?,?,?,'QUEUED',?)",run,code,"OPERATOR",ts(clock.instant()));return run;
        });
    }
    public void reconcile(long code,String source) {
        String lease=id(),run=id();Instant now=clock.instant();
        if(db.update("UPDATE payment_orders SET check_lease=?,check_until=? WHERE order_code=? AND (check_until IS NULL OR check_until<?)",lease,ts(now.plusSeconds(60)),code,ts(now))!=1)return;
        var snapshot=one(db,"SELECT * FROM payment_orders WHERE order_code=?",code);
        db.update("INSERT INTO reconciliation_runs(id,order_code,source,status,created_at) VALUES (?,?,?,'RUNNING',?)",run,code,source,ts(now));
        long started=System.nanoTime();String outcome="SUCCESS";
        try {
            var response=payos.lookup(code);
            boolean valid=Boolean.TRUE.equals(tx.execute(s->{
                var p=operations.lockOrder(code);
                return lease.equals(p.get("check_lease")) && at(p.get("check_until")).isAfter(clock.instant());
            }));
            if(!valid)return;
            operations.lookup(code,response,run,number(snapshot,"edit_version"));
            tx.executeWithoutResult(s->{
                db.update("UPDATE payment_orders SET check_attempts=0,check_lease=NULL,check_until=NULL,next_check_at=? WHERE order_code=? AND check_lease=?",
                        ts(clock.instant().plusSeconds("PAID".equals(response.path("status").asText())?86400:now.minusSeconds(86400).isAfter(at(snapshot.get("created_at")))?21600:60)),code,lease);
                db.update("UPDATE reconciliation_runs SET status='SUCCESS',result_code='VERIFIED',completed_at=? WHERE id=?",ts(clock.instant()),run);
                db.update("UPDATE reconciliation_runs SET status='SUCCESS',result_code='VERIFIED',completed_at=? WHERE order_code=? AND status='QUEUED'",ts(clock.instant()),code);
            });
        } catch(RuntimeException failure) {
            outcome="ERROR";
            int retry=failure instanceof PayOsClient.RateLimited limited?limited.retryAfter():60;
            tx.executeWithoutResult(s->{
                var p=operations.lockOrder(code);if(!lease.equals(p.get("check_lease")))return;
                int attempts=((Number)p.get("check_attempts")).intValue()+1;
                int delay=Math.max(retry,Math.min(900,30<<Math.min(5,attempts)))+ThreadLocalRandom.current().nextInt(10);
                db.update("UPDATE payment_orders SET check_attempts=?,check_lease=NULL,check_until=NULL,next_check_at=? WHERE order_code=? AND check_lease=?",attempts,ts(clock.instant().plusSeconds(delay)),code,lease);
                if(attempts>=8) operations.caseFor("provider-error:"+code,code,"PROVIDER_UNAVAILABLE","Fresh lookup repeatedly unavailable; manual review remains scheduled");
                db.update("UPDATE reconciliation_runs SET status='ERROR',result_code='PROVIDER_UNAVAILABLE',completed_at=? WHERE id=?",ts(clock.instant()),run);
            });
        } finally { metrics.timer("dictalab.payment.provider","outcome",outcome).record(Duration.ofNanos(System.nanoTime()-started)); }
    }
}
