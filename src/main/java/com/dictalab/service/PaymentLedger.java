package com.dictalab.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.math.*;
import java.util.*;
import static com.dictalab.service.PaymentData.*;

/** Call only inside the owning payment transaction. Posted rows are guarded by PostgreSQL. */
@Service
public class PaymentLedger {
    private final JdbcTemplate db;
    private final Clock clock;
    public PaymentLedger(JdbcTemplate db,Clock paymentClock) { this.db=db; this.clock=paymentClock; }

    public String post(String key,Long order,String kind,Instant effective,String debit,String credit,long amount) {
        money(amount);
        var prior=rows(db,"SELECT id FROM ledger_journals WHERE event_key=?",key);
        if(!prior.isEmpty()) return (String)prior.get(0).get("id");
        String id=id();
        db.update("INSERT INTO ledger_journals(id,event_key,order_code,kind,currency,status,effective_at,recorded_at) VALUES (?,?,?,?,'VND','DRAFT',?,?)",
                id,key,order,kind,ts(effective),ts(clock.instant()));
        db.update("INSERT INTO ledger_entries(journal_id,account_code,debit_vnd,credit_vnd) VALUES (?,?,?,0)",id,debit,amount);
        db.update("INSERT INTO ledger_entries(journal_id,account_code,debit_vnd,credit_vnd) VALUES (?,?,0,?)",id,credit,amount);
        db.update("UPDATE ledger_journals SET status='POSTED' WHERE id=? AND status='DRAFT'",id);
        return id;
    }

    public void receipt(String receipt,Long order,long amount,Instant effective) {
        post("receipt:"+receipt,order,"RECEIPT",effective,"COLLECTION_CLEARING","UNALLOCATED",amount);
    }
    public void allocate(String receipt,long order,long amount,Instant effective) {
        post("allocate:"+receipt,order,"ALLOCATION",effective,"UNALLOCATED","DEFERRED_SERVICE",amount);
    }
    public void bank(String receipt,Long order,long amount,Instant effective) {
        post("bank:"+receipt,order,"BANK_MATCH",effective,"BANK_SCOPED","COLLECTION_CLEARING",amount);
    }

    public long recognized(Map<String,Object> grant,Instant cutoff) {
        Instant start=at(grant.get("starts_at")), end=at(grant.get("ends_at"));
        long elapsed=Math.max(0,Math.min(Duration.between(start,end).getSeconds(),Duration.between(start,cutoff).getSeconds()));
        long total=number(grant,"amount_vnd"), duration=number(grant,"duration_seconds");
        long retained=duration-number(grant,"revoked_seconds");
        long cap=BigInteger.valueOf(total).multiply(BigInteger.valueOf(retained)).divide(BigInteger.valueOf(duration)).longValueExact();
        return Math.min(cap,BigInteger.valueOf(total).multiply(BigInteger.valueOf(elapsed)).divide(BigInteger.valueOf(duration)).longValueExact());
    }

    public void recognize(Map<String,Object> grant,Instant cutoff) {
        // A legacy expiry is a projection boundary, not evidence of historical service delivery.
        if("LEGACY_BASELINE".equals(grant.get("source")))return;
        long desired=recognized(grant,cutoff), previous=number(grant,"recognized_vnd");
        if(desired<=previous) return;
        long available=db.queryForObject("SELECT COALESCE(SUM(e.credit_vnd-e.debit_vnd),0) FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id WHERE j.order_code=? AND j.status='POSTED' AND e.account_code='DEFERRED_SERVICE'",BigDecimal.class,grant.get("order_code")).longValueExact();
        desired=previous+Math.min(desired-previous,Math.max(0,available));
        if(desired<=previous)return;
        post("revenue:"+grant.get("id")+":"+desired,number(grant,"order_code"),"REVENUE",cutoff,
                "DEFERRED_SERVICE","SERVICE_REVENUE",desired-previous);
        db.update("UPDATE premium_grants SET recognized_vnd=? WHERE id=?",desired,grant.get("id"));
    }

    public Map<String,Object> report(Instant cutoff) {
        var result=new LinkedHashMap<String,Object>();
        result.put("cutoff",cutoff); result.put("timezone","Asia/Ho_Chi_Minh");
        result.put("scope","IDENTIFIED_DICTALAB_TRANSACTIONS_ONLY");
        result.put("recordedThrough",clock.instant());
        result.put("receiptsVnd",sum("SELECT COALESCE(SUM(amount_vnd),0) FROM payment_receipts WHERE effective_at<=?",cutoff));
        result.put("refundsVnd",sum("SELECT COALESCE(SUM(amount_vnd),0) FROM refund_requests WHERE status='SUCCEEDED' AND updated_at<=?",cutoff));
        result.put("netReceiptsVnd",Math.subtractExact((long)result.get("receiptsVnd"),(long)result.get("refundsVnd")));
        var balances=rows(db,"""
                SELECT e.account_code,COALESCE(SUM(e.debit_vnd),0) AS debit_vnd,COALESCE(SUM(e.credit_vnd),0) AS credit_vnd
                FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id
                WHERE j.status='POSTED' AND j.effective_at<=? GROUP BY e.account_code ORDER BY e.account_code
                """,ts(cutoff));
        result.put("balances",balances);
        result.put("journalImbalances",db.queryForObject("""
                SELECT COUNT(*) FROM (SELECT j.id FROM ledger_journals j LEFT JOIN ledger_entries e ON e.journal_id=j.id
                WHERE j.status='POSTED' GROUP BY j.id HAVING COALESCE(SUM(e.debit_vnd),0)<>COALESCE(SUM(e.credit_vnd),0) OR COUNT(e.id)<2) x
                """,Long.class));
        result.put("unresolvedCases",db.queryForObject("SELECT COUNT(*) FROM reconciliation_cases WHERE status IN ('OPEN','INVESTIGATING')",Long.class));
        result.put("acceptedExceptions",db.queryForObject("SELECT COUNT(*) FROM reconciliation_cases WHERE status='ACCEPTED_EXCEPTION'",Long.class));
        return result;
    }
    private long sum(String sql,Instant cutoff) { return db.queryForObject(sql,BigDecimal.class,ts(cutoff)).longValueExact(); }
}
