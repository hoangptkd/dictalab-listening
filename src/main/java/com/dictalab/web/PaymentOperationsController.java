package com.dictalab.web;

import com.dictalab.service.*;
import com.dictalab.config.PaymentOperationsProperties;
import com.dictalab.security.DictaLabPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.servlet.http.HttpServletResponse;
import java.time.*;
import java.util.*;
import static com.dictalab.service.PaymentData.*;

@RestController
@RequestMapping("/api/admin/payments")
public class PaymentOperationsController {
    private final JdbcTemplate db;private final PaymentSecurity security;private final PaymentOperations operations;
    private final PaymentWorker worker;private final PaymentRefunds refunds;private final PaymentStatements statements;
    private final PaymentReporting reporting;private final Clock clock;private final TransactionTemplate tx;
    private final PaymentOperationsProperties config;
    public PaymentOperationsController(JdbcTemplate db,PaymentSecurity security,PaymentOperations operations,PaymentWorker worker,
            PaymentRefunds refunds,PaymentStatements statements,PaymentReporting reporting,Clock paymentClock,PlatformTransactionManager manager,PaymentOperationsProperties config) {
        this.db=db;this.security=security;this.operations=operations;this.worker=worker;this.refunds=refunds;this.statements=statements;this.reporting=reporting;this.clock=paymentClock;
        this.tx=new TransactionTemplate(manager);tx.setTimeout(10);this.config=config;
    }
    @ModelAttribute public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal DictaLabPrincipal actor) {
        return Map.of("permissions",security.permissions(actor.id()),"actorId",actor.id(),"totpEnabled",Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM payment_totp WHERE user_id=? AND enabled=TRUE",Boolean.class,actor.id())),
                "refundsEnabled",config.refundsEnabled(),"partialRefundsEnabled",config.partialRefundsEnabled(),"automaticPayouts",false);
    }
    @GetMapping("/orders") public Map<String,Object> orders(@AuthenticationPrincipal DictaLabPrincipal actor,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String status,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
            @RequestParam(defaultValue="0") long minAmount,@RequestParam(defaultValue="1000000000000") long maxAmount) {
        security.require(actor.id(),"PAYMENT_VIEW");
        if(page<0 || page>100000 || size<1 || size>100 || minAmount<0 || maxAmount<minAmount || maxAmount>MAX_MONEY || q.length()>20
                || !status.isEmpty() && !List.of("CREATING","PENDING","PAID","CANCELLED","EXPIRED").contains(status))throw error(HttpStatus.BAD_REQUEST,"PAYMENT_FILTER_INVALID");
        var args=new ArrayList<Object>();String where=" WHERE amount_vnd>=? AND amount_vnd<=?";args.add(minAmount);args.add(maxAmount);
        if(!q.isBlank()) { if(!q.matches("[0-9]{1,16}"))throw error(HttpStatus.BAD_REQUEST,"PAYMENT_ORDER_INVALID");where+=" AND order_code=?";args.add(Long.parseLong(q)); }
        if(!status.isEmpty()){where+=" AND status=?";args.add(status);}
        if(from!=null){where+=" AND created_at>=?";args.add(ts(from.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        if(to!=null){where+=" AND created_at<?";args.add(ts(to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));}
        long total=db.queryForObject("SELECT COUNT(*) FROM payment_orders"+where,Long.class,args.toArray());
        var all=new ArrayList<>(args);all.add(size);all.add(Math.multiplyExact(page,size));
        var items=rows(db,"SELECT order_code,amount_vnd,duration_days,status,currency,product_code,policy_version,created_at,paid_at,expires_at,edit_version FROM payment_orders"+where+" ORDER BY created_at DESC,order_code DESC LIMIT ? OFFSET ?",all.toArray());
        return Map.of("items",items,"page",page,"size",size,"total",total);
    }
    @GetMapping("/orders/{code}") public Map<String,Object> detail(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable long code) {
        security.require(actor.id(),"PAYMENT_VIEW");
        var p=one(db,"SELECT * FROM payment_orders WHERE order_code=?",code);p.remove("checkout_url");p.remove("payment_reference");p.remove("user_id");
        p.put("receipts",rows(db,"SELECT id,reference,amount_vnd,currency,effective_at,recorded_at,bank_verified FROM payment_receipts WHERE order_code=? ORDER BY recorded_at,id",code).stream().map(r->{
            String ref=(String)r.get("reference");r.put("reference",ref.length()>4?"••••"+ref.substring(ref.length()-4):"••••");
            r.put("allocationBinding",hash("ALLOCATE|"+r.get("id")+"|"+code+"|"+p.get("edit_version")+"|"+p.get("amount_vnd")));return r;}).toList());
        p.put("events",rows(db,"SELECT id,source,kind,payload_hash,recorded_at FROM payment_events WHERE order_code=? ORDER BY recorded_at,id LIMIT 200",code));
        p.put("grants",rows(db,"SELECT id,source,starts_at,ends_at,duration_seconds,revoked_seconds,recognized_vnd FROM premium_grants WHERE order_code=?",code));
        p.put("journals",rows(db,"SELECT j.id,j.kind,j.effective_at,j.recorded_at,e.account_code,e.debit_vnd,e.credit_vnd FROM ledger_journals j JOIN ledger_entries e ON e.journal_id=j.id WHERE j.order_code=? ORDER BY j.recorded_at,j.id,e.id LIMIT 500",code));
        p.put("cases",rows(db,"SELECT * FROM reconciliation_cases WHERE order_code=? ORDER BY created_at,id",code));
        p.put("refunds",rows(db,"SELECT id,amount_vnd,status,version,reason,recipient_mask,created_at FROM refund_requests WHERE order_code=? ORDER BY created_at,id",code));
        p.put("runs",rows(db,"SELECT * FROM reconciliation_runs WHERE order_code=? ORDER BY created_at DESC LIMIT 50",code));
        operations.audit(actor.id(),"PAYMENT_DETAIL_VIEW",Long.toString(code),"Masked transaction timeline");return p;
    }
    public record Reason(@NotBlank @Size(max=1000) String reason) {}
    public record Allocation(@NotBlank @Size(max=36) String receiptId,@Min(0) long expectedVersion,@NotBlank String challenge,@NotBlank @Size(max=800) String reason) {}
    @PostMapping("/orders/{code}/allocate-reviewed") public Map<String,Object> allocate(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable long code,@Valid @RequestBody Allocation body) {
        return operations.allocateReviewed(actor.id(),code,body.receiptId(),body.expectedVersion(),body.reason(),body.challenge(),security);
    }
    @PostMapping("/orders/{code}/reconcile") public Map<String,String> reconcile(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable long code,@Valid @RequestBody Reason reason) {
        security.require(actor.id(),"PAYMENT_RECONCILE");return Map.of("jobId",worker.request(code,actor.id()),"status","QUEUED");
    }
    @GetMapping("/reconciliation-cases") public Map<String,Object> cases(@AuthenticationPrincipal DictaLabPrincipal actor,@RequestParam(defaultValue="0") int page) {
        security.require(actor.id(),"PAYMENT_VIEW");if(page<0 || page>100000)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_PAGE_INVALID");
        return Map.of("items",rows(db,"SELECT * FROM reconciliation_cases ORDER BY CASE WHEN status IN ('OPEN','INVESTIGATING') THEN 0 ELSE 1 END,due_at,id LIMIT 25 OFFSET ?",page*25),
                "total",db.queryForObject("SELECT COUNT(*) FROM reconciliation_cases",Long.class),"page",page,"size",25);
    }
    public record CaseAction(@NotBlank String action,@Min(0) long expectedVersion,@NotBlank @Size(max=1000) String reason,@Size(max=1000) String evidence) {}
    @PostMapping("/reconciliation-cases/{id}/actions") public Map<String,Object> caseAction(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody CaseAction body) {
        security.require(actor.id(),"PAYMENT_RECONCILE");
        return tx.execute(s->{
            db.queryForObject("SELECT id FROM payment_mutex WHERE id=1 FOR UPDATE",Integer.class);
            var c=one(db,"SELECT * FROM reconciliation_cases WHERE id=? FOR UPDATE",id);
            if(number(c,"version")!=body.expectedVersion())throw error(HttpStatus.CONFLICT,"PAYMENT_VERSION_CONFLICT");
            if(!List.of("OPEN","INVESTIGATING").contains(c.get("status")))throw error(HttpStatus.CONFLICT,"PAYMENT_CASE_CLOSED");
            String next=switch(body.action()) {case "CLAIM","REPLAY"->"INVESTIGATING";case "RESOLVE"->"RESOLVED";case "ACCEPT_EXCEPTION"->"ACCEPTED_EXCEPTION";default->throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CASE_ACTION_INVALID");};
            if(!body.action().equals("CLAIM") && (body.evidence()==null || body.evidence().strip().length()<10))throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CASE_EVIDENCE_REQUIRED");
            if(body.action().equals("REPLAY")) {
                String key=(String)c.get("case_key");if(!key.startsWith("inbox:"))throw error(HttpStatus.CONFLICT,"PAYMENT_CASE_NOT_REPLAYABLE");
                db.update("UPDATE payment_inbox SET status='RECEIVED',attempts=0,next_attempt=?,lease_id=NULL,locked_until=NULL WHERE event_id=? AND status='QUARANTINED'",ts(clock.instant()),key.substring(6));
            }
            String resolution=body.reason()+"; evidence="+Objects.toString(body.evidence(),"");
            if(resolution.length()>1000)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_CASE_REASON_TOO_LONG");
            db.update("UPDATE reconciliation_cases SET status=?,owner_id=?,resolution=?,version=version+1,updated_at=? WHERE id=?",next,actor.id(),resolution,ts(clock.instant()),id);
            operations.audit(actor.id(),"CASE_"+body.action(),id,body.reason());return one(db,"SELECT * FROM reconciliation_cases WHERE id=?",id);
        });
    }
    public record Statement(@NotBlank @Size(max=262144) String csv,@NotBlank @Size(max=80) String source) {}
    @PostMapping("/statements/preview") public Map<String,Object> statement(@AuthenticationPrincipal DictaLabPrincipal actor,@Valid @RequestBody Statement body) { return statements.preview(actor.id(),body.csv(),body.source()); }
    public record ConfirmStatement(@NotBlank String fileHash,@NotBlank @Size(max=1000) String reason) {}
    @GetMapping("/statements/{id}") public Map<String,Object> statementDetail(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id) { return statements.detail(actor.id(),id); }
    @PostMapping("/statements/{id}/confirm") public Map<String,Object> confirmStatement(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody ConfirmStatement body) { return statements.confirm(actor.id(),id,body.fileHash(),body.reason()); }
    @GetMapping("/statements") public List<Map<String,Object>> statements(@AuthenticationPrincipal DictaLabPrincipal actor) {
        security.require(actor.id(),"PAYMENT_RECONCILE");return rows(db,"SELECT * FROM statement_imports ORDER BY created_at DESC LIMIT 50");
    }
    public record TotpStart(@NotBlank @Size(max=72) String password) {}
    public record Otp(@NotBlank @Pattern(regexp="[0-9]{6}") String otp) {}
    public record Challenge(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String binding,@NotBlank @Pattern(regexp="[0-9]{6}") String otp) {}
    @PostMapping("/security/totp/enroll") public Map<String,Object> enroll(@AuthenticationPrincipal DictaLabPrincipal actor,@Valid @RequestBody TotpStart body) { return security.beginTotp(actor.id(),body.password()); }
    @PostMapping("/security/totp/confirm") public Map<String,Boolean> confirmTotp(@AuthenticationPrincipal DictaLabPrincipal actor,@Valid @RequestBody Otp body) { security.enableTotp(actor.id(),body.otp());return Map.of("enabled",true); }
    @PostMapping("/security/challenges") public Map<String,String> challenge(@AuthenticationPrincipal DictaLabPrincipal actor,@Valid @RequestBody Challenge body) { return Map.of("challenge",security.challenge(actor.id(),body.binding(),body.otp())); }
    public record Permissions(@NotNull Set<String> permissions,@NotBlank String challenge,@NotBlank @Size(max=1000) String reason) {}
    @PostMapping("/permissions/{user}") public Map<String,Boolean> permissions(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable long user,@Valid @RequestBody Permissions body) {
        security.setPermissions(actor.id(),user,body.permissions(),body.challenge(),body.reason());return Map.of("updated",true);
    }
    @GetMapping("/refunds") public Map<String,Object> refunds(@AuthenticationPrincipal DictaLabPrincipal actor,@RequestParam(defaultValue="0") int page) {
        security.require(actor.id(),"PAYMENT_VIEW");if(page<0 || page>100000)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_PAGE_INVALID");
        return Map.of("items",rows(db,"SELECT id,order_code,amount_vnd,status,version,recipient_mask,reason,created_at FROM refund_requests ORDER BY created_at DESC,id LIMIT 25 OFFSET ?",page*25),"page",page,"size",25,"total",db.queryForObject("SELECT COUNT(*) FROM refund_requests",Long.class));
    }
    @GetMapping("/refunds/{id}") public Map<String,Object> refund(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id) { security.require(actor.id(),"PAYMENT_VIEW");return refunds.detail(id); }
    public record RefundCommand(@Min(0) long expectedVersion,@Size(max=36) String challenge,@NotBlank @Size(max=1000) String reason,
            @Size(max=64) String evidenceLineKey,@Size(max=64) String recipientFingerprint) {}
    @PostMapping("/refunds/{id}/approve") public Map<String,Object> approve(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody RefundCommand body) { return refunds.approve(actor.id(),id,body.expectedVersion(),body.challenge(),body.reason()); }
    @PostMapping("/refunds/{id}/reject") public Map<String,Object> reject(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody RefundCommand body) { return refunds.reject(actor.id(),id,body.expectedVersion(),body.reason()); }
    @PostMapping("/refunds/{id}/cancel-approval") public Map<String,Object> cancelApproval(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody RefundCommand body) { return refunds.cancelApproval(actor.id(),id,body.expectedVersion(),body.challenge(),body.reason()); }
    @PostMapping("/refunds/{id}/execution-evidence") public Map<String,Object> execute(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody RefundCommand body) { return refunds.executionEvidence(actor.id(),id,body.expectedVersion(),body.evidenceLineKey(),body.recipientFingerprint(),body.challenge(),body.reason()); }
    @PostMapping("/refunds/{id}/confirm") public Map<String,Object> confirm(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody RefundCommand body) { return refunds.confirm(actor.id(),id,body.expectedVersion(),body.challenge(),body.reason()); }
    public record RefundBody(@Min(1) @Max(1000000000000L) long amountVnd,@NotBlank @Size(max=1000) String reason,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String recipientFingerprint,@NotBlank @Size(max=80) String recipientMask) {}
    @PostMapping("/orders/{code}/refund-requests") public Map<String,Object> requestRefund(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable long code,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody RefundBody body) { return refunds.request(actor.id(),code,body.amountVnd(),body.reason(),body.recipientFingerprint(),body.recipientMask(),key,true); }
    @GetMapping("/orders/{code}/refund-preview") public Map<String,Object> refundPreview(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable long code,@RequestParam long amountVnd) { return refunds.preview(actor.id(),code,amountVnd,true); }
    @GetMapping("/reports") public Map<String,Object> report(@AuthenticationPrincipal DictaLabPrincipal actor,@RequestParam(required=false) Instant cutoff) { return reporting.report(actor.id(),cutoff==null?clock.instant():cutoff); }
    @GetMapping("/audit") public List<Map<String,Object>> audit(@AuthenticationPrincipal DictaLabPrincipal actor,@RequestParam(defaultValue="0") int page) {
        security.require(actor.id(),"PAYMENT_AUDIT");if(page<0 || page>100000)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_PAGE_INVALID");operations.audit(actor.id(),"AUDIT_VIEW","AUDIT","page="+page);return rows(db,"SELECT * FROM payment_audit ORDER BY created_at DESC,id DESC LIMIT 100 OFFSET ?",page*100);
    }
    @GetMapping(value="/export",produces="text/csv") public ResponseEntity<String> export(@AuthenticationPrincipal DictaLabPrincipal actor,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        security.require(actor.id(),"PAYMENT_AUDIT");
        LocalDate end=to==null?LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"))):to,start=from==null?end.minusDays(30):from;
        if(start.isAfter(end) || java.time.temporal.ChronoUnit.DAYS.between(start,end)>366)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_EXPORT_RANGE_INVALID");
        var result=rows(db,"SELECT order_code,amount_vnd,currency,status,created_at,paid_at FROM payment_orders WHERE created_at>=? AND created_at<? ORDER BY created_at,order_code LIMIT 10001",ts(start.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()),ts(end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()));
        if(result.size()>10000)throw error(HttpStatus.BAD_REQUEST,"PAYMENT_EXPORT_NARROW_DATE_RANGE");
        StringBuilder csv=new StringBuilder("orderCode,amountVnd,currency,status,createdAt,paidAt\r\n");
        for(var row:result)csv.append(List.of("order_code","amount_vnd","currency","status","created_at","paid_at").stream().map(k->PaymentStatements.csvCell(row.get(k))).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        operations.audit(actor.id(),"PAYMENT_EXPORT","CSV","from="+start+"; to="+end+"; count="+result.size());
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=dictalab-payments.csv").header("Cache-Control","no-store").body(csv.toString());
    }
    public record Close(@NotNull LocalDate date,@NotBlank @Size(max=1000) String reason,String reopenId) {}
    @PostMapping("/closes") public Map<String,Object> close(@AuthenticationPrincipal DictaLabPrincipal actor,@Valid @RequestBody Close body) { return reporting.close(actor.id(),body.date(),body.reason(),body.reopenId()); }
    @GetMapping("/closes") public List<Map<String,Object>> closes(@AuthenticationPrincipal DictaLabPrincipal actor) { security.require(actor.id(),"PAYMENT_AUDIT");return rows(db,"SELECT * FROM payment_daily_closes ORDER BY business_date DESC,version DESC LIMIT 100"); }
    @PostMapping("/closes/{id}/reopen") public Map<String,String> reopen(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody Reason body) { return Map.of("id",reporting.requestReopen(actor.id(),id,body.reason())); }
    public record ReopenApproval(@NotBlank String challenge,@NotBlank @Size(max=1000) String reason) {}
    @GetMapping("/close-reopens") public List<Map<String,Object>> reopens(@AuthenticationPrincipal DictaLabPrincipal actor) {
        security.require(actor.id(),"PAYMENT_AUDIT");return rows(db,"SELECT r.*,c.business_date FROM payment_close_reopens r JOIN payment_daily_closes c ON c.id=r.close_id ORDER BY r.created_at DESC LIMIT 100").stream().map(r->{r.put("binding",hash("REOPEN|"+r.get("id")+"|"+r.get("close_id")+"|"+r.get("reason")));return r;}).toList();
    }
    @PostMapping("/close-reopens/{id}/approve") public Map<String,Boolean> reopenApprove(@AuthenticationPrincipal DictaLabPrincipal actor,@PathVariable String id,@Valid @RequestBody ReopenApproval body) { reporting.approveReopen(actor.id(),id,body.challenge(),body.reason());return Map.of("approved",true); }
}
