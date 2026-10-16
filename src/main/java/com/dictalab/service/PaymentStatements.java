package com.dictalab.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.dictalab.service.PaymentData.*;

@Service
public class PaymentStatements {
    private final JdbcTemplate db;
    private final PaymentSecurity security;
    private final PaymentOperations operations;
    private final PaymentLedger ledger;
    private final Clock clock;
    private final TransactionTemplate tx;
    public PaymentStatements(JdbcTemplate db,PaymentSecurity security,PaymentOperations operations,PaymentLedger ledger,Clock paymentClock,PlatformTransactionManager manager) {
        this.db=db;this.security=security;this.operations=operations;this.ledger=ledger;this.clock=paymentClock;tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    public Map<String,Object> preview(long actor,String csv,String source) {
        security.require(actor,"PAYMENT_RECONCILE");text(source,80);
        if(csv==null || csv.getBytes(StandardCharsets.UTF_8).length>262144)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"PAYMENT_STATEMENT_TOO_LARGE");
        var records=parse(csv);
        if(records.isEmpty() || !records.get(0).equals(List.of("reference","amountVnd","direction","date","scope")))throw error(HttpStatus.BAD_REQUEST,"PAYMENT_STATEMENT_HEADER_INVALID");
        if(records.size()<2 || records.size()>2001)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_STATEMENT_ROW_LIMIT");
        var normalized=new ArrayList<Map<String,Object>>();
        for(var row:records.subList(1,records.size())) {
            if(row.size()!=5)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_STATEMENT_COLUMNS_INVALID");
            String ref=text(row.get(0),150),direction=row.get(2),scope=row.get(4);long amount;
            LocalDate date;
            try { if(!row.get(1).matches("[1-9][0-9]{0,12}"))throw new IllegalArgumentException();amount=money(Long.parseLong(row.get(1)));date=LocalDate.parse(row.get(3)); }
            catch(RuntimeException bad) { throw error(HttpStatus.BAD_REQUEST,"PAYMENT_STATEMENT_VALUE_INVALID"); }
            if(!List.of("IN","OUT").contains(direction) || !List.of("DICTALAB","PERSONAL").contains(scope))throw error(HttpStatus.BAD_REQUEST,"PAYMENT_STATEMENT_SCOPE_INVALID");
            normalized.add(Map.of("reference",ref,"amount",amount,"direction",direction,"date",date,"scope",scope,"key",hash(ref+"|"+amount+"|"+direction+"|"+date)));
        }
        return tx.execute(s->{
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            var prior=rows(db,"SELECT id FROM statement_imports WHERE file_hash=?",hash(csv));
            if(!prior.isEmpty())return detail(actor,(String)prior.get(0).get("id"));
            String id=id();
            db.update("INSERT INTO statement_imports(id,file_hash,mapping_version,source,status,actor_id,line_count,created_at) VALUES (?,?,'NORMALIZED_V1',?,'PREVIEW',?,?,?)",id,hash(csv),source,actor,normalized.size(),ts(clock.instant()));
            Set<String> seen=new HashSet<>();
            for(var row:normalized)if(seen.add((String)row.get("key")))db.update("INSERT INTO statement_lines(id,import_id,line_key,reference,amount_vnd,direction,business_date,scope) VALUES (?,?,?,?,?,?,?,?)",
                    id(),id,row.get("key"),row.get("reference"),row.get("amount"),row.get("direction"),java.sql.Date.valueOf((LocalDate)row.get("date")),row.get("scope"));
            operations.audit(actor,"STATEMENT_PREVIEW",id,"Normalized CSV; source="+source+"; rows="+normalized.size());return detail(actor,id);
        });
    }
    public Map<String,Object> detail(long actor,String id) {
        security.require(actor,"PAYMENT_RECONCILE");
        var result=one(db,"SELECT * FROM statement_imports WHERE id=?",id);
        result.put("lines",rows(db,"SELECT id,line_key,reference,amount_vnd,direction,business_date,scope,matched_receipt FROM statement_lines WHERE import_id=? ORDER BY business_date,id LIMIT 2000",id));return result;
    }
    public Map<String,Object> confirm(long actor,String id,String fileHash,String reason) {
        security.require(actor,"PAYMENT_RECONCILE");text(reason,1000);
        return tx.execute(s->{
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            var imported=one(db,"SELECT * FROM statement_imports WHERE id=? FOR UPDATE",id);
            if(!Objects.equals(imported.get("file_hash"),fileHash))throw error(HttpStatus.CONFLICT,"PAYMENT_STATEMENT_HASH_CHANGED");
            if("CONFIRMED".equals(imported.get("status")))return detail(actor,id);
            for(var line:rows(db,"SELECT * FROM statement_lines WHERE import_id=? ORDER BY id",id)) {
                if(!"DICTALAB".equals(line.get("scope")))continue;
                var existing=rows(db,"SELECT * FROM bank_evidence WHERE reference=? AND direction=?",line.get("reference"),line.get("direction"));
                if(!existing.isEmpty()) {
                    if(existing.stream().anyMatch(e->!e.get("line_key").equals(line.get("line_key")))) operations.caseFor("bank-conflict:"+line.get("line_key"),null,"BANK_REFERENCE_CONFLICT","Overlapping statements disagree about a bank reference");
                    continue;
                }
                db.update("INSERT INTO bank_evidence(line_key,reference,amount_vnd,direction,business_date,statement_line_id,created_at) VALUES (?,?,?,?,?,?,?)",
                        line.get("line_key"),line.get("reference"),line.get("amount_vnd"),line.get("direction"),line.get("business_date"),line.get("id"),ts(clock.instant()));
                if(!"IN".equals(line.get("direction")))continue;
                var receipts=rows(db,"SELECT * FROM payment_receipts WHERE reference=? AND amount_vnd=?",line.get("reference"),line.get("amount_vnd"));
                if(receipts.size()!=1) {
                    operations.caseFor("statement-unmatched:"+line.get("line_key"),null,"BANK_UNMATCHED","Identified bank line has no unique matching provider receipt");continue;
                }
                var receipt=receipts.get(0);
                LocalDate effective=at(receipt.get("effective_at")).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
                LocalDate statementDate=((java.sql.Date)line.get("business_date")).toLocalDate();
                if(Math.abs(java.time.temporal.ChronoUnit.DAYS.between(effective,statementDate))>1) {
                    operations.caseFor("statement-date:"+line.get("line_key"),(Long)receipt.get("order_code"),"BANK_DATE_MISMATCH","Reference/amount match but date requires review");continue;
                }
                ledger.bank((String)receipt.get("id"),(Long)receipt.get("order_code"),number(receipt,"amount_vnd"),statementDate.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
                db.update("UPDATE payment_receipts SET bank_verified=TRUE WHERE id=?",receipt.get("id"));
                db.update("UPDATE statement_lines SET matched_receipt=? WHERE id=?",receipt.get("id"),line.get("id"));
            }
            db.update("UPDATE statement_imports SET status='CONFIRMED',confirmed_at=? WHERE id=?",ts(clock.instant()),id);
            operations.audit(actor,"STATEMENT_CONFIRMED",id,reason);return detail(actor,id);
        });
    }
    /** Bounded RFC4180 subset; rejects embedded NUL, unterminated quotes and characters after a closing quote. */
    static List<List<String>> parse(String csv) {
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false,closed=false;
        if(csv.startsWith("\ufeff"))csv=csv.substring(1);
        for(int i=0;i<csv.length();i++) {
            char c=csv.charAt(i);if(c=='\0')throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CSV_INVALID");
            if(quoted) {
                if(c=='"') { if(i+1<csv.length() && csv.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;} }
                else cell.append(c);
            } else if(c=='"' && cell.isEmpty() && !closed) quoted=true;
            else if(c==',' || c=='\n' || c=='\r') {
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=',') { if(c=='\r' && i+1<csv.length() && csv.charAt(i+1)=='\n')i++;if(!(row.size()==1 && row.get(0).isEmpty()))rows.add(row);row=new ArrayList<>(); }
            } else { if(closed || c=='"')throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CSV_INVALID");cell.append(c); }
            if(cell.length()>2000 || row.size()>6 || rows.size()>2001)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CSV_LIMIT");
        }
        if(quoted)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CSV_INVALID");
        if(!cell.isEmpty() || !row.isEmpty() || closed){row.add(cell.toString());rows.add(row);}return rows;
    }
    public static String csvCell(Object value) {
        String text=Objects.toString(value,"");
        if(text.matches("(?s)^[\\s]*[=+@-].*"))text="'"+text;
        return '"'+text.replace("\"","\"\"")+'"';
    }
}
