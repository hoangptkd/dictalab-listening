package com.dictalab.web;
import com.dictalab.service.*;
import com.dictalab.security.DictaLabPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.*;

@RestController
public class PaymentRefundController {
    private final PaymentRefunds refunds;private final org.springframework.jdbc.core.JdbcTemplate db;
    public PaymentRefundController(PaymentRefunds refunds,org.springframework.jdbc.core.JdbcTemplate db) { this.refunds=refunds;this.db=db; }
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @PostMapping("/api/billing/orders/{code}/refund-requests")
    public Map<String,Object> request(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable long code,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody PaymentOperationsController.RefundBody body) {
        return refunds.request(user.id(),code,body.amountVnd(),body.reason(),body.recipientFingerprint(),body.recipientMask(),key,false);
    }
    @GetMapping("/api/billing/orders/{code}/refund-preview")
    public Map<String,Object> preview(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable long code,@RequestParam long amountVnd) { return refunds.preview(user.id(),code,amountVnd,false); }
    @GetMapping("/api/billing/refund-requests")
    public List<Map<String,Object>> mine(@AuthenticationPrincipal DictaLabPrincipal user) {
        return PaymentData.rows(db,"SELECT r.id,r.order_code,r.amount_vnd,r.status,r.created_at,r.ticket_id FROM refund_requests r JOIN payment_orders p ON p.order_code=r.order_code WHERE p.user_id=? ORDER BY r.created_at DESC LIMIT 30",user.id());
    }
}
