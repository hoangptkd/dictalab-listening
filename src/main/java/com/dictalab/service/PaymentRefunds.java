package com.dictalab.service;

import com.dictalab.config.PaymentOperationsProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import static com.dictalab.service.PaymentData.*;

/** Manual bank execution only. Approval reserves funds; independent bank evidence confirms completion. */
@Service
public class PaymentRefunds {
    private final JdbcTemplate db;
    private final PaymentOperations operations;
    private final PaymentLedger ledger;
    private final PaymentSecurity security;
    private final PaymentOperationsProperties config;
    private final Clock clock;
    private final TransactionTemplate tx;
    public PaymentRefunds(JdbcTemplate db,PaymentOperations operations,PaymentLedger ledger,PaymentSecurity security,
            PaymentOperationsProperties config,Clock paymentClock,PlatformTransactionManager manager) {
        this.db=db;this.operations=operations;this.ledger=ledger;this.security=security;this.config=config;this.clock=paymentClock;
        tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    private void enabled() { if(!config.refundsEnabled()) throw error(HttpStatus.CONFLICT,"PAYMENT_REFUNDS_MANUAL_REVIEW_ONLY"); }
    public Map<String,Object> preview(long actor,long code,long amount,boolean operator) {
        if(operator)security.require(actor,"REFUND_REQUEST");
        var p=one(db,"SELECT * FROM payment_orders WHERE order_code=?"+(operator?"":" AND user_id=?"),operator?new Object[]{code}:new Object[]{code,actor});
        var grant=one(db,"SELECT * FROM premium_grants WHERE order_code=?",code);
        money(amount);
        long allocated=db.queryForObject("SELECT COALESCE(SUM(amount_vnd),0) FROM payment_allocations WHERE order_code=?",Long.class,code);
        long held=db.queryForObject("SELECT COALESCE(SUM(amount_vnd),0) FROM refund_requests WHERE order_code=? AND status IN ('APPROVED','EXECUTING','UNKNOWN','SUCCEEDED')",Long.class,code);
        long refundable=Math.max(0,allocated-held);
        if(amount>refundable)throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_EXCEEDS_VERIFIED_FUNDS");
        long seconds=BigInteger.valueOf(number(grant,"duration_seconds")).multiply(BigInteger.valueOf(amount)).divide(BigInteger.valueOf(number(grant,"amount_vnd"))).longValueExact();
        long future=Math.max(0,Duration.between(clock.instant(),at(grant.get("ends_at"))).getSeconds());
        seconds=Math.min(seconds,Math.min(future,number(grant,"duration_seconds")-number(grant,"revoked_seconds")));
        return Map.of("orderCode",code,"amountVnd",amount,"refundableVnd",refundable,"secondsToRevoke",seconds,
                "policyVersion",p.get("policy_version"),"partial",amount<number(p,"amount_vnd"),"manualReview",true);
    }
    private long refundable(long code) {
        long verified=db.queryForObject("SELECT COALESCE(SUM(a.amount_vnd),0) FROM payment_allocations a JOIN payment_receipts r ON r.id=a.receipt_id WHERE a.order_code=? AND r.bank_verified=TRUE",Long.class,code);
        long reserved=db.queryForObject("SELECT COALESCE(SUM(amount_vnd),0) FROM refund_requests WHERE order_code=? AND status IN ('APPROVED','EXECUTING','UNKNOWN','SUCCEEDED')",Long.class,code);
        return Math.max(0,Math.subtractExact(verified,reserved));
    }
    public Map<String,Object> request(long actor,long code,long amount,String reason,String recipientFingerprint,String recipientMask,String key,boolean operator) {
        if(operator)security.require(actor,"REFUND_REQUEST");
        text(reason,1000);text(recipientMask,80);
        String maskedRecipient=recipientMask.replaceAll("[0-9]{5,}","••••");
        if(recipientFingerprint==null || !recipientFingerprint.matches("[a-f0-9]{64}"))throw error(HttpStatus.BAD_REQUEST,"PAYMENT_RECIPIENT_FINGERPRINT_REQUIRED");
        text(key,100);
        return tx.execute(s->{
            var p=operations.lockOrder(code);
            if(p.isEmpty() || !operator && !Objects.equals(p.get("user_id"),actor)) throw error(HttpStatus.NOT_FOUND,"PAYMENT_RESOURCE_NOT_FOUND");
            if(!"PAID".equals(p.get("status")))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_REQUIRES_PAID_ORDER");
            String body=hash(code+"|"+amount+"|"+reason+"|"+recipientFingerprint+"|"+maskedRecipient);
            var prior=rows(db,"SELECT * FROM payment_idempotency WHERE actor_id=? AND operation='REQUEST_REFUND' AND request_key=?",actor,key);
            if(!prior.isEmpty()) {
                if(!body.equals(prior.get(0).get("request_hash")))throw error(HttpStatus.CONFLICT,"PAYMENT_IDEMPOTENCY_CONFLICT");
                return detail((String)prior.get(0).get("resource_id"));
            }
            var preview=preview(actor,code,amount,operator);
            String id=id();Long owner=(Long)p.get("user_id");Long ticket=null;
            if(owner!=null) {
                var keys=new org.springframework.jdbc.support.GeneratedKeyHolder();
                db.update(connection->{
                    var statement=connection.prepareStatement("INSERT INTO support_tickets(user_id,category,subject,description,reference_type,reference_id) VALUES (?,'PAYMENT',?,?,'PAYMENT',?)",new String[]{"id"});
                    statement.setLong(1,owner);statement.setString(2,"Yêu cầu hoàn tiền đơn "+code);statement.setString(3,reason);statement.setString(4,Long.toString(code));return statement;
                },keys);ticket=keys.getKey().longValue();
            }
            db.update("INSERT INTO refund_requests(id,order_code,requested_by,amount_vnd,seconds_to_revoke,reason,recipient_hash,recipient_mask,status,policy_version,ticket_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'REQUESTED',?,?,?,?)",
                    id,code,actor,amount,preview.get("secondsToRevoke"),reason,recipientFingerprint,maskedRecipient,p.get("policy_version"),ticket,ts(clock.instant()),ts(clock.instant()));
            db.update("INSERT INTO payment_idempotency(actor_id,operation,request_key,request_hash,resource_id,created_at) VALUES (?,'REQUEST_REFUND',?,?,?,?)",actor,key,body,id,ts(clock.instant()));
            operations.audit(actor,"REFUND_REQUESTED",id,reason);return detail(id);
        });
    }
    public Map<String,Object> detail(String id) {
        var r=one(db,"SELECT * FROM refund_requests WHERE id=?",id);
        r.put("approveBinding",binding("APPROVE",r));r.put("executeBinding",binding("EXECUTE",r));r.put("confirmBinding",binding("CONFIRM",r));r.put("cancelBinding",binding("CANCEL",r));
        return r;
    }
    public String binding(String action,Map<String,Object> r) {
        return hash(action+"|"+r.get("id")+"|"+r.get("version")+"|"+r.get("amount_vnd")+"|"+r.get("seconds_to_revoke")+"|"+r.get("recipient_hash")+"|"+r.get("policy_version")+"|"+r.get("evidence_line_key"));
    }
    private Map<String,Object> lock(String id) {
        var snapshot=one(db,"SELECT order_code FROM refund_requests WHERE id=?",id);
        operations.lockOrder(number(snapshot,"order_code"));
        return one(db,"SELECT * FROM refund_requests WHERE id=? FOR UPDATE",id);
    }
    private void version(Map<String,Object> r,long expected) {
        if(number(r,"version")!=expected)throw error(HttpStatus.CONFLICT,"PAYMENT_VERSION_CONFLICT");
    }
    public Map<String,Object> approve(long actor,String id,long version,String challenge,String reason) {
        security.require(actor,"REFUND_APPROVE");enabled();text(reason,1000);
        return tx.execute(s->{
            var r=lock(id);version(r,version);
            if(!"REQUESTED".equals(r.get("status")))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_STATE_INVALID");
            var baseline=one(db,"SELECT source FROM premium_grants WHERE order_code=?",r.get("order_code"));
            if("LEGACY_BASELINE".equals(baseline.get("source")))throw error(HttpStatus.CONFLICT,"PAYMENT_LEGACY_REFUND_REQUIRES_MANUAL_RECONSTRUCTION");
            if(Objects.equals(r.get("requested_by"),actor))throw error(HttpStatus.FORBIDDEN,"PAYMENT_REFUND_SELF_APPROVAL_FORBIDDEN");
            if(!config.partialRefundsEnabled() && number(r,"amount_vnd")!=db.queryForObject("SELECT amount_vnd FROM payment_orders WHERE order_code=?",Long.class,r.get("order_code")))
                throw error(HttpStatus.CONFLICT,"PAYMENT_PARTIAL_REFUNDS_DISABLED");
            if(number(r,"amount_vnd")>refundable(number(r,"order_code")))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_EXCEEDS_VERIFIED_FUNDS");
            if(db.queryForObject("SELECT COUNT(*) FROM payment_receipt_sources s JOIN payment_allocations a ON a.receipt_id=s.receipt_id WHERE a.order_code=? AND s.payer_fingerprint=?",Integer.class,r.get("order_code"),r.get("recipient_hash"))!=1)
                throw error(HttpStatus.CONFLICT,"PAYMENT_RECIPIENT_SOURCE_NOT_VERIFIED");
            security.consume(actor,challenge,binding("APPROVE",r));
            String approved=hash(r.get("amount_vnd")+"|"+r.get("recipient_hash")+"|"+r.get("policy_version")+"|"+r.get("seconds_to_revoke"));
            db.update("UPDATE refund_requests SET status='APPROVED',approved_by=?,approved_at=?,approval_hash=?,approval_expires_at=?,version=version+1,updated_at=? WHERE id=?",
                    actor,ts(clock.instant()),approved,ts(clock.instant().plusSeconds(86400)),ts(clock.instant()),id);
            var g=one(db,"SELECT * FROM premium_grants WHERE order_code=? FOR UPDATE",r.get("order_code"));
            ledger.recognize(g,clock.instant());g=one(db,"SELECT * FROM premium_grants WHERE id=?",g.get("id"));
            long amount=number(r,"amount_vnd"),deferred=Math.min(amount,Math.max(0,
                    db.queryForObject("SELECT COALESCE(SUM(e.credit_vnd-e.debit_vnd),0) FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE j.order_code=? AND j.status='POSTED' AND e.account_code='DEFERRED_SERVICE'",java.math.BigDecimal.class,r.get("order_code")).longValueExact()));
            if(deferred>0)ledger.post("refund-reserve:"+id+":deferred",number(r,"order_code"),"REFUND_RESERVE",clock.instant(),"DEFERRED_SERVICE","REFUND_PAYABLE",deferred);
            if(amount>deferred)ledger.post("refund-reserve:"+id+":revenue",number(r,"order_code"),"REFUND_RESERVE",clock.instant(),"REVENUE_REVERSAL","REFUND_PAYABLE",amount-deferred);
            operations.audit(actor,"REFUND_APPROVED",id,reason);return detail(id);
        });
    }
    public Map<String,Object> reject(long actor,String id,long version,String reason) {
        security.require(actor,"REFUND_APPROVE");text(reason,1000);
        return tx.execute(s->{
            var r=lock(id);version(r,version);
            if(!"REQUESTED".equals(r.get("status")))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_STATE_INVALID");
            if(Objects.equals(r.get("requested_by"),actor))throw error(HttpStatus.FORBIDDEN,"PAYMENT_REFUND_SELF_APPROVAL_FORBIDDEN");
            db.update("UPDATE refund_requests SET status='REJECTED',version=version+1,updated_at=? WHERE id=?",ts(clock.instant()),id);
            operations.audit(actor,"REFUND_REJECTED",id,reason);return detail(id);
        });
    }
    public Map<String,Object> cancelApproval(long actor,String id,long version,String challenge,String reason) {
        security.require(actor,"REFUND_APPROVE");text(reason,1000);
        return tx.execute(s->{
            var r=lock(id);version(r,version);
            if(!"APPROVED".equals(r.get("status")))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_STATE_INVALID");
            if(Objects.equals(r.get("requested_by"),actor))throw error(HttpStatus.FORBIDDEN,"PAYMENT_REFUND_SELF_APPROVAL_FORBIDDEN");
            security.consume(actor,challenge,binding("CANCEL",r));
            for(var journal:rows(db,"SELECT * FROM ledger_journals WHERE event_key IN (?,?)", "refund-reserve:"+id+":deferred","refund-reserve:"+id+":revenue")) {
                var entries=rows(db,"SELECT * FROM ledger_entries WHERE journal_id=? ORDER BY id",journal.get("id"));
                var debit=entries.stream().filter(e->number(e,"debit_vnd")>0).findFirst().orElseThrow();
                var credit=entries.stream().filter(e->number(e,"credit_vnd")>0).findFirst().orElseThrow();
                ledger.post("refund-release:"+journal.get("id"),number(r,"order_code"),"REFUND_RELEASE",clock.instant(),(String)credit.get("account_code"),(String)debit.get("account_code"),number(debit,"debit_vnd"));
            }
            db.update("UPDATE refund_requests SET status='CANCELLED',version=version+1,updated_at=? WHERE id=?",ts(clock.instant()),id);
            operations.audit(actor,"REFUND_APPROVAL_CANCELLED",id,"No execution was recorded; "+reason);return detail(id);
        });
    }
    public Map<String,Object> executionEvidence(long actor,String id,long version,String line,String recipientFingerprint,String challenge,String reason) {
        security.require(actor,"REFUND_EXECUTE");enabled();text(reason,1000);
        return tx.execute(s->{
            var r=lock(id);version(r,version);
            boolean attaching="UNKNOWN".equals(r.get("status")) && r.get("evidence_line_key")==null && line!=null && !line.isBlank();
            if(!attaching && (!"APPROVED".equals(r.get("status")) || at(r.get("approval_expires_at")).isBefore(clock.instant())))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_APPROVAL_EXPIRED");
            if(!Objects.equals(r.get("recipient_hash"),recipientFingerprint))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_RECIPIENT_CHANGED");
            security.consume(actor,challenge,binding("EXECUTE",r));
            String expected=hash(r.get("amount_vnd")+"|"+r.get("recipient_hash")+"|"+r.get("policy_version")+"|"+r.get("seconds_to_revoke"));
            if(!expected.equals(r.get("approval_hash")))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_APPROVAL_INVALID");
            if(line!=null && !line.isBlank()) {
                var evidence=one(db,"SELECT * FROM bank_evidence WHERE line_key=? AND direction='OUT' FOR UPDATE",line);
                if(number(evidence,"amount_vnd")!=number(r,"amount_vnd"))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_EVIDENCE_AMOUNT_MISMATCH");
            }
            db.update("UPDATE refund_requests SET status='UNKNOWN',executed_by=?,evidence_line_key=?,version=version+1,updated_at=? WHERE id=?",attaching?r.get("executed_by"):actor,line==null || line.isBlank()?null:line,ts(clock.instant()),id);
            db.update("INSERT INTO refund_attempts(id,refund_id,attempt_key,result,evidence,created_at) VALUES (?,?,?,'UNKNOWN',?,?)",id(),id,"manual:"+id+":"+version,reason.substring(0,Math.min(500,reason.length())),ts(clock.instant()));
            operations.audit(actor,"REFUND_EXECUTION_RECORDED",id,"Await independent bank evidence confirmation; "+reason);return detail(id);
        });
    }
    public Map<String,Object> confirm(long actor,String id,long version,String challenge,String reason) {
        security.require(actor,"REFUND_APPROVE");enabled();text(reason,1000);
        return tx.execute(s->{
            var r=lock(id);version(r,version);
            if(!"UNKNOWN".equals(r.get("status")) || r.get("evidence_line_key")==null)throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_BANK_EVIDENCE_REQUIRED");
            if(Objects.equals(r.get("executed_by"),actor) || Objects.equals(r.get("requested_by"),actor))throw error(HttpStatus.FORBIDDEN,"PAYMENT_REFUND_INDEPENDENT_CHECKER_REQUIRED");
            security.consume(actor,challenge,binding("CONFIRM",r));
            var evidence=one(db,"SELECT * FROM bank_evidence WHERE line_key=? AND direction='OUT' FOR UPDATE",r.get("evidence_line_key"));
            long importer=db.queryForObject("SELECT i.actor_id FROM statement_lines l JOIN statement_imports i ON i.id=l.import_id WHERE l.id=?",Long.class,evidence.get("statement_line_id"));
            if(importer==actor)throw error(HttpStatus.FORBIDDEN,"PAYMENT_REFUND_INDEPENDENT_CHECKER_REQUIRED");
            if(number(evidence,"amount_vnd")!=number(r,"amount_vnd"))throw error(HttpStatus.CONFLICT,"PAYMENT_REFUND_EVIDENCE_MISMATCH");
            db.update("UPDATE refund_requests SET status='SUCCEEDED',version=version+1,updated_at=? WHERE id=?",ts(clock.instant()),id);
            ledger.post("refund-paid:"+id,number(r,"order_code"),"REFUND_PAID",clock.instant(),"REFUND_PAYABLE","BANK_SCOPED",number(r,"amount_vnd"));
            adjust(r);
            db.update("INSERT INTO refund_attempts(id,refund_id,attempt_key,result,evidence,created_at) VALUES (?,?,?,'SUCCEEDED',?,?)",id(),id,"confirm:"+id,"bank-line="+r.get("evidence_line_key"),ts(clock.instant()));
            operations.audit(actor,"REFUND_CONFIRMED",id,reason);return detail(id);
        });
    }
    private void adjust(Map<String,Object> refund) {
        var grant=one(db,"SELECT * FROM premium_grants WHERE order_code=? FOR UPDATE",refund.get("order_code"));
        long remaining=number(grant,"duration_seconds")-number(grant,"revoked_seconds");
        long removed=Math.min(number(refund,"seconds_to_revoke"),Math.min(remaining,Math.max(0,Duration.between(clock.instant(),at(grant.get("ends_at"))).getSeconds())));
        db.update("INSERT INTO premium_adjustments(id,grant_id,refund_id,seconds_removed,reason,created_at) VALUES (?,?,?,?,?,?)",id(),grant.get("id"),refund.get("id"),removed,refund.get("reason"),ts(clock.instant()));
        if(removed==0)return;
        Instant oldEnd=at(grant.get("ends_at")),newEnd=oldEnd.minusSeconds(removed);
        db.update("UPDATE premium_grants SET revoked_seconds=revoked_seconds+?,ends_at=? WHERE id=?",removed,ts(newEnd),grant.get("id"));
        Long user=(Long)grant.get("user_id");if(user==null)return;
        for(var following:rows(db,"SELECT * FROM premium_grants WHERE user_id=? AND starts_at>=? AND id<>? ORDER BY starts_at,id FOR UPDATE",user,ts(oldEnd),grant.get("id"))) {
            db.update("UPDATE premium_grants SET starts_at=?,ends_at=? WHERE id=?",ts(at(following.get("starts_at")).minusSeconds(removed)),ts(at(following.get("ends_at")).minusSeconds(removed)),following.get("id"));
        }
        var end=rows(db,"SELECT MAX(ends_at) AS ends_at FROM premium_grants WHERE user_id=?",user);
        if(!end.isEmpty() && end.get(0).get("ends_at")!=null)db.update("UPDATE premium_accounts SET premium_until=? WHERE user_id=?",end.get(0).get("ends_at"),user);
    }
}
