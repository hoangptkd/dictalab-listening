package com.dictalab.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.dictalab.service.PaymentData.*;

@Service
public class PaymentReporting {
    private final JdbcTemplate db;private final PaymentLedger ledger;private final PaymentSecurity security;
    private final PaymentOperations operations;private final Clock clock;private final ObjectMapper json;private final TransactionTemplate tx;
    public PaymentReporting(JdbcTemplate db,PaymentLedger ledger,PaymentSecurity security,PaymentOperations operations,Clock paymentClock,ObjectMapper json,PlatformTransactionManager manager) {
        this.db=db;this.ledger=ledger;this.security=security;this.operations=operations;this.clock=paymentClock;this.json=json;tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    public Map<String,Object> report(long actor,Instant cutoff) {
        security.require(actor,"PAYMENT_AUDIT");if(cutoff.isAfter(clock.instant()))throw error(HttpStatus.BAD_REQUEST,"PAYMENT_REPORT_FUTURE_CUTOFF");
        operations.audit(actor,"REPORT_VIEW","VND","cutoff="+cutoff);return ledger.report(cutoff);
    }
    public Map<String,Object> close(long actor,LocalDate date,String reason,String reopenId) {
        security.require(actor,"PAYMENT_AUDIT");text(reason,1000);
        Instant cutoff=date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        if(cutoff.isAfter(clock.instant()))throw error(HttpStatus.CONFLICT,"PAYMENT_CLOSE_DAY_NOT_FINISHED");
        return tx.execute(s->{
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            var previous=rows(db,"SELECT * FROM payment_daily_closes WHERE business_date=? ORDER BY version DESC",java.sql.Date.valueOf(date));
            int version=1;
            if(!previous.isEmpty()) {
                if(reopenId==null || reopenId.isBlank())return previous.get(0);
                var reopen=one(db,"SELECT * FROM payment_close_reopens WHERE id=? AND close_id=? AND approved_at IS NOT NULL",reopenId,previous.get(0).get("id"));
                version=((Number)previous.get(0).get("version")).intValue()+1;
            }
            for(var g:rows(db,"SELECT * FROM premium_grants WHERE created_at<=? ORDER BY id FOR UPDATE",ts(cutoff)))ledger.recognize(g,cutoff);
            var snapshot=ledger.report(cutoff);String body=json.writeValueAsString(snapshot),id=id();
            db.update("INSERT INTO payment_daily_closes(id,business_date,version,cutoff,snapshot,digest,actor_id,reason,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    id,java.sql.Date.valueOf(date),version,ts(cutoff),body,hash(body),actor,reason,ts(clock.instant()));
            operations.audit(actor,"DAILY_CLOSE",id,reason);return one(db,"SELECT * FROM payment_daily_closes WHERE id=?",id);
        });
    }
    public String requestReopen(long actor,String close,String reason) {
        security.require(actor,"PAYMENT_AUDIT");text(reason,1000);one(db,"SELECT id FROM payment_daily_closes WHERE id=?",close);
        String id=id();db.update("INSERT INTO payment_close_reopens(id,close_id,requested_by,reason,created_at) VALUES (?,?,?,?,?)",id,close,actor,reason,ts(clock.instant()));
        operations.audit(actor,"CLOSE_REOPEN_REQUESTED",id,reason);return id;
    }
    public void approveReopen(long actor,String id,String challenge,String reason) {
        security.require(actor,"PAYMENT_AUDIT");text(reason,1000);
        tx.executeWithoutResult(s->{
            var r=one(db,"SELECT * FROM payment_close_reopens WHERE id=? FOR UPDATE",id);
            if(number(r,"requested_by")==actor || r.get("approved_at")!=null)throw error(HttpStatus.FORBIDDEN,"PAYMENT_CLOSE_INDEPENDENT_CHECKER_REQUIRED");
            security.consume(actor,challenge,hash("REOPEN|"+id+"|"+r.get("close_id")+"|"+r.get("reason")));
            db.update("UPDATE payment_close_reopens SET approved_by=?,approved_at=? WHERE id=?",actor,ts(clock.instant()),id);operations.audit(actor,"CLOSE_REOPEN_APPROVED",id,reason);
        });
    }
}
