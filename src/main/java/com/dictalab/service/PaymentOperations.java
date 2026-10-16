package com.dictalab.service;

import com.dictalab.config.BillingProperties;
import com.dictalab.config.PaymentOperationsProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.dictalab.service.PaymentData.*;

@Service
public class PaymentOperations {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final BillingProperties billing;
    private final PaymentOperationsProperties config;
    private final PaymentLedger ledger;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    public PaymentOperations(JdbcTemplate db,ObjectMapper json,BillingProperties billing,PaymentOperationsProperties config,
            PaymentLedger ledger,Clock paymentClock,PlatformTransactionManager manager,MeterRegistry metrics) {
        this.db=db;this.json=json;this.billing=billing;this.config=config;this.ledger=ledger;this.clock=paymentClock;
        this.metrics=metrics;this.tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    String channel() { return hash(billing.clientId()).substring(0,32); }
    public boolean checkoutEnabled() { return config.checkoutEnabled(); }
    public String policyVersion() { return config.policyVersion(); }
    public void rejectedWebhook() { metrics.counter("dictalab.payment.webhook","outcome","rejected").increment(); }

    public String receive(JsonNode payload) {
        if(!config.intakeEnabled()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_INTAKE_PAUSED");
        JsonNode data=payload.path("data");
        if(!payload.path("success").asBoolean() || !"00".equals(payload.path("code").asText()) || !"00".equals(data.path("code").asText()))
            return record("WEBHOOK","PROVIDER_NOTICE",null,Map.of("code",data.path("code").asText()),false);
        long code=orderCode(data.path("orderCode"));
        long amount=money(data.path("amount"));
        String link=text(data.path("paymentLinkId").asText(),64),reference=text(data.path("reference").asText(),150);
        if(!link.matches("[A-Za-z0-9-]{1,64}")) throw error(HttpStatus.BAD_REQUEST,"PAYMENT_LINK_INVALID");
        var safe=new TreeMap<String,Object>();
        safe.put("orderCode",code);safe.put("amount",amount);safe.put("currency",text(data.path("currency").asText(),3));
        safe.put("paymentLinkId",link);safe.put("reference",reference);
        safe.put("transactionDateTime",data.path("transactionDateTime").asText(""));
        safe.put("payerFingerprint",payer(data));
        boolean sample=code==123 && amount==3000 && reference.equals("TF230204212323")
                && link.equals("124c33293c43417ab7879e14c8d9eb18") && data.path("accountNumber").asText().equals("12345678");
        String event=record("WEBHOOK",sample?"VERIFICATION_SAMPLE":"RECEIPT",code,safe,!sample);
        metrics.counter("dictalab.payment.webhook","outcome","accepted").increment();
        return event;
    }

    private long orderCode(JsonNode value) {
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<1 || value.asLong()>9_007_199_254_740_991L)
            throw error(HttpStatus.BAD_REQUEST,"PAYMENT_ORDER_INVALID");return value.asLong();
    }

    String record(String source,String kind,Long code,Object payload,boolean inbox) {
        String body=json.writeValueAsString(payload),digest=hash(body),key=channel()+":"+kind+":"+digest;
        return tx.execute(status->{
            // Low volume merchant: serialize receipt identity insertion, including unmatched events.
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            var old=rows(db,"SELECT id FROM payment_events WHERE event_key=?",key);
            String event=old.isEmpty()?id():(String)old.get(0).get("id");
            if(old.isEmpty()) {
                db.update("INSERT INTO payment_events(id,event_key,order_code,source,kind,payload_hash,payload,recorded_at) VALUES (?,?,?,?,?,?,?,?)",
                        event,key,code,source,kind,digest,body,ts(clock.instant()));
                if(inbox) db.update("INSERT INTO payment_inbox(event_id,status,next_attempt) VALUES (?,'RECEIVED',?)",event,ts(clock.instant()));
            } else metrics.counter("dictalab.payment.webhook","outcome","duplicate").increment();
            if(source.equals("WEBHOOK")) db.update("INSERT INTO payment_deliveries(id,event_id,received_at,duplicate) VALUES (?,?,?,?)",id(),event,ts(clock.instant()),!old.isEmpty());
            return event;
        });
    }

    /** An ACK only depends on receive() committing. Processing failures remain in the durable inbox. */
    public void process(String event) {
        String lease=id();Instant now=clock.instant();
        int claimed=db.update("""
                UPDATE payment_inbox SET status='PROCESSING',attempts=attempts+1,lease_id=?,locked_until=?
                WHERE event_id=? AND attempts<8 AND ((status IN ('RECEIVED','RETRY') AND next_attempt<=?)
                OR (status='PROCESSING' AND locked_until<?))
                """,lease,ts(now.plusSeconds(60)),event,ts(now),ts(now));
        if(claimed!=1) return;
        try {
            tx.executeWithoutResult(status->{
                db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
                var job=one(db,"SELECT * FROM payment_inbox WHERE event_id=? AND lease_id=? AND status='PROCESSING' FOR UPDATE",event,lease);
                if(at(job.get("locked_until")).isBefore(clock.instant())) return;
                var source=one(db,"SELECT * FROM payment_events WHERE id=?",event);
                JsonNode data=json.readTree((String)source.get("payload"));
                boolean applied=apply(event,data,(String)source.get("source"));
                db.update("UPDATE payment_inbox SET status=?,lease_id=NULL,locked_until=NULL,last_error=? WHERE event_id=? AND lease_id=?",
                        applied?"PROCESSED":"QUARANTINED",applied?null:"BUSINESS_EXCEPTION",event,lease);
            });
        } catch(RuntimeException failure) {
            var attempts=rows(db,"SELECT attempts FROM payment_inbox WHERE event_id=? AND lease_id=?",event,lease);
            if(attempts.isEmpty()) return;
            int count=((Number)attempts.get(0).get("attempts")).intValue();
            String state=count>=8?"QUARANTINED":"RETRY";
            db.update("UPDATE payment_inbox SET status=?,next_attempt=?,lease_id=NULL,locked_until=NULL,last_error='PROCESSING_FAILED' WHERE event_id=? AND lease_id=?",
                    state,ts(clock.instant().plusSeconds(Math.min(900,5L<<count)+ThreadLocalRandom.current().nextInt(5))),event,lease);
            metrics.counter("dictalab.payment.inbox","outcome",state.toLowerCase(Locale.ROOT)).increment();
            if(count>=8) tx.executeWithoutResult(s->caseFor("inbox:"+event,null,"RETRY_EXHAUSTED","Inbox processing exhausted; event="+event));
        }
    }

    private boolean apply(String event,JsonNode data,String source) {
        long code=orderCode(data.path("orderCode")), amount=money(data.path("amount"));
        String link=data.path("paymentLinkId").asText(),ref=data.path("reference").asText();
        var order=lockOrder(code);
        db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
        String content=hash(code+"|"+link+"|"+amount+"|"+data.path("currency").asText());
        var existing=rows(db,"SELECT * FROM payment_receipts WHERE provider='PAYOS' AND channel=? AND reference=?",channel(),ref);
        if(!existing.isEmpty()) {
            if(!content.equals(existing.get(0).get("content_hash"))) {
                caseFor("reference-conflict:"+channel()+":"+hash(ref),code,"REFERENCE_CONFLICT","Same reference has different signed content");return false;
            }
            sourceEvidence((String)existing.get(0).get("id"),event,data.path("payerFingerprint").asText(""));
            if(!order.isEmpty()) verifyProjection(order);
            return true;
        }
        if(!"VND".equals(data.path("currency").asText())) {
            caseFor("currency:"+event,code,"CURRENCY_MISMATCH","Signed currency is not VND");return false;
        }
        Instant effective=providerTime(data.path("transactionDateTime").asText(""));
        String receipt=id();
        db.update("INSERT INTO payment_receipts(id,channel,reference,order_code,payment_link_id,amount_vnd,currency,content_hash,event_id,effective_at,recorded_at) VALUES (?,?,?,?,?,?,'VND',?,?,?,?)",
                receipt,channel(),ref,code,link,amount,content,event,ts(effective),ts(clock.instant()));
        ledger.receipt(receipt,code,amount,effective);
        sourceEvidence(receipt,event,data.path("payerFingerprint").asText(""));
        if(order.isEmpty()) { caseFor("unmatched:"+receipt,code,"UNMATCHED","Verified receipt without a known order; receipt="+receipt);return false; }
        if(order.get("payment_link_id")!=null && !link.equals(order.get("payment_link_id"))) {
            caseFor("link:"+receipt,code,"LINK_MISMATCH","Receipt link does not match order; receipt="+receipt);return false;
        }
        if(amount!=number(order,"amount_vnd")) {
            caseFor("amount:"+receipt,code,amount<number(order,"amount_vnd")?"PARTIAL":"OVERPAID","Verified receipt requires manual review; receipt="+receipt);return false;
        }
        var grant=rows(db,"SELECT id FROM premium_grants WHERE order_code=?",code);
        if(!grant.isEmpty() || "PAID".equals(order.get("status"))) {
            caseFor("extra:"+receipt,code,"EXTRA_PAYMENT","Additional receipt after payment; no additional entitlement");return false;
        }
        db.update("INSERT INTO payment_allocations(receipt_id,order_code,amount_vnd,created_at) VALUES (?,?,?,?)",receipt,code,amount,ts(clock.instant()));
        ledger.allocate(receipt,code,amount,effective);
        grant(order,source);
        metrics.timer("dictalab.payment.grant.latency").record(Duration.between(at(one(db,"SELECT recorded_at FROM payment_events WHERE id=?",event).get("recorded_at")),clock.instant()).abs());
        db.update("UPDATE payment_orders SET status='PAID',paid_at=?,payment_reference=?,payment_link_id=?,edit_version=edit_version+1,next_check_at=? WHERE order_code=?",
                ts(clock.instant()),ref,link,ts(clock.instant().plusSeconds(86400)),code);
        resolveRecovery(code);
        audit(null,"PAYMENT_APPLIED",Long.toString(code),"receipt="+receipt+"; source="+source);
        return true;
    }

    Map<String,Object> lockOrder(long code) {
        db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
        var initial=rows(db,"SELECT user_id FROM payment_orders WHERE order_code=?",code);
        if(initial.isEmpty()) return Map.of();
        if(initial.get(0).get("user_id")!=null) rows(db,"SELECT id FROM app_users WHERE id=? FOR UPDATE",initial.get(0).get("user_id"));
        var locked=rows(db,"SELECT * FROM payment_orders WHERE order_code=? FOR UPDATE",code);
        return locked.isEmpty()?Map.of():locked.get(0);
    }
    public Map<String,Object> allocateReviewed(long actor,long code,String receipt,long expectedVersion,String reason,String challenge,PaymentSecurity security) {
        security.require(actor,"PAYMENT_RECONCILE");text(reason,1000);
        return tx.execute(s->{
            var order=lockOrder(code);if(order.isEmpty())throw error(HttpStatus.NOT_FOUND,"PAYMENT_RESOURCE_NOT_FOUND");
            if(number(order,"edit_version")!=expectedVersion)throw error(HttpStatus.CONFLICT,"PAYMENT_VERSION_CONFLICT");
            var r=one(db,"SELECT * FROM payment_receipts WHERE id=? FOR UPDATE",receipt);
            if(!Boolean.TRUE.equals(r.get("bank_verified")) || !Objects.equals(r.get("order_code"),code)
                    || !Objects.equals(r.get("payment_link_id"),order.get("payment_link_id")) || number(r,"amount_vnd")<number(order,"amount_vnd")
                    || "PAID".equals(order.get("status")) || !rows(db,"SELECT id FROM premium_grants WHERE order_code=?",code).isEmpty()
                    || !rows(db,"SELECT receipt_id FROM payment_allocations WHERE receipt_id=?",receipt).isEmpty())throw error(HttpStatus.CONFLICT,"PAYMENT_REVIEWED_ALLOCATION_NOT_ELIGIBLE");
            security.consume(actor,challenge,hash("ALLOCATE|"+receipt+"|"+code+"|"+expectedVersion+"|"+order.get("amount_vnd")));
            db.update("INSERT INTO payment_allocations(receipt_id,order_code,amount_vnd,created_at) VALUES (?,?,?,?)",receipt,code,order.get("amount_vnd"),ts(clock.instant()));
            ledger.allocate(receipt,code,number(order,"amount_vnd"),clock.instant());grant(order,"REVIEWED_ALLOCATION");
            db.update("UPDATE payment_orders SET status='PAID',paid_at=?,payment_reference=?,edit_version=edit_version+1 WHERE order_code=?",ts(clock.instant()),r.get("reference"),code);
            audit(actor,"RECEIPT_ALLOCATED",receipt,reason+"; surplus remains unallocated");return Map.of("orderCode",code,"allocatedVnd",order.get("amount_vnd"),"surplusVnd",number(r,"amount_vnd")-number(order,"amount_vnd"));
        });
    }
    private void grant(Map<String,Object> order,String source) {
        long code=number(order,"order_code"),seconds=Math.multiplyExact(number(order,"duration_days"),86400L);
        Long user=(Long)order.get("user_id");Instant now=clock.instant(),start=now;
        if(user!=null) {
            var account=rows(db,"SELECT premium_until FROM premium_accounts WHERE user_id=?",user);
            if(!account.isEmpty() && at(account.get(0).get("premium_until")).isAfter(now)) start=at(account.get(0).get("premium_until"));
            Instant end=start.plusSeconds(seconds);
            if(account.isEmpty()) db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)",user,ts(end));
            else db.update("UPDATE premium_accounts SET premium_until=? WHERE user_id=?",ts(end),user);
            db.update("INSERT INTO account_audit(subject_id,action,detail) VALUES (?,'PREMIUM_PAYMENT',?)",user,"payOS order="+code+", days="+order.get("duration_days")+", until="+end);
            db.update("INSERT INTO payment_outbox(id,event_key,user_id,order_code,kind,next_attempt,created_at) VALUES (?,?,?,?,'PREMIUM_GRANTED',?,?)",
                    id(),"grant:"+code,user,code,ts(now),ts(now));
        }
        db.update("INSERT INTO premium_grants(id,order_code,user_id,source,starts_at,ends_at,duration_seconds,amount_vnd,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id(),code,user,user==null?"DETACHED_RECEIPT":source,ts(start),ts(start.plusSeconds(seconds)),seconds,number(order,"amount_vnd"),ts(now));
        metrics.counter("dictalab.payment.grants","source",source.toLowerCase(Locale.ROOT)).increment();
    }

    public void lookup(long code,JsonNode result,String run) {
        lookup(code,result,run,null);
    }
    public void lookup(long code,JsonNode result,String run,Long expectedVersion) {
        if(orderCode(result.path("orderCode"))!=code) throw error(HttpStatus.BAD_GATEWAY,"PAYMENT_PROVIDER_ORDER_MISMATCH");
        var order=one(db,"SELECT * FROM payment_orders WHERE order_code=?",code);
        if(money(result.path("amount"))!=number(order,"amount_vnd")) throw error(HttpStatus.BAD_GATEWAY,"PAYMENT_PROVIDER_AMOUNT_MISMATCH");
        String link=text(result.path("id").asText(),64);
        if(!link.matches("[A-Za-z0-9-]{1,64}") || order.get("payment_link_id")!=null && !order.get("payment_link_id").equals(link))
            throw error(HttpStatus.BAD_GATEWAY,"PAYMENT_PROVIDER_LINK_MISMATCH");
        // A lookup is fresh provider evidence; individual bank references dedupe with webhooks.
        JsonNode transactions=result.path("transactions");
        boolean any=false;
        if(transactions.isArray()) for(JsonNode t:transactions) {
            if(!t.path("reference").isTextual() || t.path("reference").asText().isBlank()) continue;
            long amount=money(t.path("amount"));
            var payload=new TreeMap<String,Object>();payload.put("orderCode",code);payload.put("amount",amount);
            payload.put("currency","VND");payload.put("paymentLinkId",link);payload.put("reference",text(t.path("reference").asText(),150));
            payload.put("transactionDateTime",t.path("transactionDateTime").asText(""));
            payload.put("payerFingerprint",payer(t));
            String event=record("LOOKUP","RECEIPT",code,payload,true);process(event);any=true;
        }
        boolean hasTransactions=any;
        tx.executeWithoutResult(s->{
            var current=lockOrder(code);
            if(current.isEmpty()) return;
            if(!hasTransactions && "PAID".equals(result.path("status").asText()) && !"PAID".equals(current.get("status")))
                caseFor("lookup-no-references:"+code,code,"MISSING_RECEIPT_REFERENCES","Provider reports PAID without usable bank transaction references; run="+run);
            if((expectedVersion==null || number(current,"edit_version")==expectedVersion) && !"PAID".equals(current.get("status")) && List.of("PENDING","CANCELLED","EXPIRED").contains(result.path("status").asText()))
                db.update("UPDATE payment_orders SET status=?,payment_link_id=?,checkout_url=?,edit_version=edit_version+1 WHERE order_code=? AND status<>'PAID'",
                        result.path("status").asText(),link,"https://pay.payos.vn/web/"+link,code);
            verifyProjection(current);
            if("PAID".equals(current.get("status")) && !"PAID".equals(result.path("status").asText()))
                caseFor("provider-status:"+code,code,"PROVIDER_STATUS_MISMATCH","Paid order conflicts with fresh provider status; run="+run);
            record("LOOKUP","STATUS",code,Map.of("providerStatus",result.path("status").asText(),"amountPaid",result.path("amountPaid").asLong(),"run",run),false);
        });
    }

    private void verifyProjection(Map<String,Object> order) {
        if(!"PAID".equals(order.get("status"))) return;
        long code=number(order,"order_code");
        var grants=rows(db,"SELECT * FROM premium_grants WHERE order_code=?",code);
        if(grants.isEmpty()) { caseFor("missing-grant:"+code,code,"MISSING_GRANT","Paid order has no entitlement evidence; backfill/review required");return; }
        var g=grants.get(0);Long user=(Long)order.get("user_id");
        if(user!=null && at(g.get("ends_at")).isAfter(clock.instant())) {
            var account=rows(db,"SELECT premium_until FROM premium_accounts WHERE user_id=?",user);
            if(account.isEmpty() || at(account.get(0).get("premium_until")).isBefore(at(g.get("ends_at")))) {
                // Restore the existing grant projection only; never award the duration again.
                if(account.isEmpty()) db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)",user,g.get("ends_at"));
                else db.update("UPDATE premium_accounts SET premium_until=? WHERE user_id=?",g.get("ends_at"),user);
                audit(null,"PROJECTION_REPAIRED",Long.toString(code),"Restored an existing grant boundary");
            }
        }
        long allocations=db.queryForObject("SELECT COUNT(*) FROM payment_allocations WHERE order_code=?",Long.class,code);
        if(allocations==0) caseFor("missing-ledger:"+code,code,"MISSING_ALLOCATION","Paid order lacks a verified allocated receipt");
    }
    private void resolveRecovery(long code) {
        db.update("UPDATE reconciliation_cases SET status='RESOLVED',resolution='Verified payment applied',version=version+1,updated_at=? WHERE order_code=? AND status IN ('OPEN','INVESTIGATING') AND kind IN ('MISSING_GRANT','MISSING_RECEIPT_REFERENCES')",ts(clock.instant()),code);
    }
    public String caseFor(String key,Long code,String kind,String evidence) {
        var prior=rows(db,"SELECT id FROM reconciliation_cases WHERE case_key=?",key);
        if(!prior.isEmpty()) return (String)prior.get(0).get("id");
        String id=id();
        db.update("INSERT INTO reconciliation_cases(id,case_key,order_code,kind,status,evidence,created_at,due_at,updated_at) VALUES (?,?,?,?,'OPEN',?,?,?,?)",
                id,key,code,kind,evidence,ts(clock.instant()),ts(clock.instant().plusSeconds(86400)),ts(clock.instant()));
        metrics.counter("dictalab.payment.cases","kind",kind.toLowerCase(Locale.ROOT)).increment();return id;
    }
    public void audit(Long actor,String action,String resource,String reason) {
        db.update("INSERT INTO payment_audit(id,actor_id,action,resource_id,reason,correlation_id,created_at) VALUES (?,?,?,?,?,?,?)",
                id(),actor,action,resource,text(reason,1000),id(),ts(clock.instant()));
    }
    private Instant providerTime(String input) {
        try { return Instant.parse(input); } catch(RuntimeException ignored) {}
        try { return LocalDateTime.parse(input,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(); }
        catch(RuntimeException ignored) { return clock.instant(); }
    }
    private String payer(JsonNode data) {
        String bank=data.path("counterAccountBankId").asText(""),account=data.path("counterAccountNumber").asText("");
        return bank.matches("[A-Za-z0-9]{3,20}") && account.matches("[0-9]{4,30}")?hash(bank+"|"+account):"";
    }
    private void sourceEvidence(String receipt,String event,String fingerprint) {
        if(!fingerprint.matches("[a-f0-9]{64}"))return;
        var old=rows(db,"SELECT payer_fingerprint FROM payment_receipt_sources WHERE receipt_id=?",receipt);
        if(old.isEmpty())db.update("INSERT INTO payment_receipt_sources(receipt_id,payer_fingerprint,event_id,created_at) VALUES (?,?,?,?)",receipt,fingerprint,event,ts(clock.instant()));
        else if(!fingerprint.equals(old.get(0).get("payer_fingerprint")))caseFor("payer-conflict:"+receipt,null,"PAYER_SOURCE_CONFLICT","Signed source-account fingerprints disagree");
    }

    /** Bounded, repeatable historical baseline. No outbound calls, emails or additional entitlement. */
    public int backfill() {
        var orders=rows(db,"SELECT p.order_code FROM payment_orders p WHERE p.status='PAID' AND NOT EXISTS (SELECT 1 FROM premium_grants g WHERE g.order_code=p.order_code) ORDER BY p.order_code LIMIT 20");
        for(var selected:orders) tx.executeWithoutResult(s->{
            long code=number(selected,"order_code");var p=lockOrder(code);
            if(p.isEmpty() || !rows(db,"SELECT id FROM premium_grants WHERE order_code=?",code).isEmpty()) return;
            Instant paid=p.get("paid_at")==null?clock.instant():at(p.get("paid_at"));
            Long user=(Long)p.get("user_id");long seconds=number(p,"duration_days")*86400;
            var account=user==null?List.<Map<String,Object>>of():rows(db,"SELECT premium_until FROM premium_accounts WHERE user_id=?",user);
            Instant end=account.isEmpty()?paid.plusSeconds(seconds):at(account.get(0).get("premium_until"));
            db.update("INSERT INTO premium_grants(id,order_code,user_id,source,starts_at,ends_at,duration_seconds,amount_vnd,created_at) VALUES (?,?,?,'LEGACY_BASELINE',?,?,?,?,?)",
                    id(),code,user,ts(end.minusSeconds(seconds)),ts(end),seconds,number(p,"amount_vnd"),ts(clock.instant()));
            if(p.get("payment_reference")==null || p.get("payment_link_id")==null) { caseFor("legacy-evidence:"+code,code,"LEGACY_EVIDENCE_MISSING","Baseline preserved; receipt evidence requires fresh reconciliation");return; }
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            String ref=(String)p.get("payment_reference"),link=(String)p.get("payment_link_id");
            var receiptBefore=rows(db,"SELECT * FROM payment_receipts WHERE channel=? AND reference=?",channel(),ref);
            if(!receiptBefore.isEmpty()) {
                var r=receiptBefore.get(0);
                if(Objects.equals(r.get("order_code"),code) && number(r,"amount_vnd")==number(p,"amount_vnd") && link.equals(r.get("payment_link_id"))
                        && rows(db,"SELECT receipt_id FROM payment_allocations WHERE receipt_id=?",r.get("id")).isEmpty()) {
                    db.update("INSERT INTO payment_allocations(receipt_id,order_code,amount_vnd,created_at) VALUES (?,?,?,?)",r.get("id"),code,p.get("amount_vnd"),ts(clock.instant()));
                    ledger.allocate((String)r.get("id"),code,number(p,"amount_vnd"),paid);
                }
                audit(null,"LEGACY_BASELINE",Long.toString(code),"Preserved expiry and linked existing receipt; no new entitlement");return;
            }
            String event=record("LEGACY","BASELINE",code,Map.of("orderCode",code,"basis","EXISTING_PAID_RECORD"),false), receipt=id();long amount=number(p,"amount_vnd");
            db.update("INSERT INTO payment_receipts(id,channel,reference,order_code,payment_link_id,amount_vnd,currency,content_hash,event_id,effective_at,recorded_at) VALUES (?,?,?,?,?,?,'VND',?,?,?,?)",
                    receipt,channel(),ref,code,link,amount,hash(code+"|"+link+"|"+amount+"|VND"),event,ts(paid),ts(clock.instant()));
            ledger.receipt(receipt,code,amount,paid);
            db.update("INSERT INTO payment_allocations(receipt_id,order_code,amount_vnd,created_at) VALUES (?,?,?,?)",receipt,code,amount,ts(clock.instant()));
            ledger.allocate(receipt,code,amount,paid);
            audit(null,"LEGACY_BASELINE",Long.toString(code),"Preserved existing premium expiry; no notification or new entitlement");
        });return orders.size();
    }
}
