package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.BillingService;
import com.dictalab.service.PremiumAccessService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.List;

@RestController
public class BillingController {
    private final BillingService billing;
    private final PremiumAccessService access;
    private final ObjectMapper json;
    public BillingController(BillingService billing, PremiumAccessService access, ObjectMapper json) { this.billing=billing; this.access=access; this.json=json; }
    @GetMapping("/api/billing/status")
    public ResponseEntity<Map<String,Object>> status(@AuthenticationPrincipal DictaLabPrincipal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.status(user.id()));
    }
    @GetMapping("/api/billing/orders")
    public ResponseEntity<List<Map<String,Object>>> orders(@AuthenticationPrincipal DictaLabPrincipal user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(billing.orders(user.id()));
    }
    @PostMapping("/api/billing/orders")
    public Map<String,Object> create(@AuthenticationPrincipal DictaLabPrincipal user,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody(required=false) JsonNode body) {
        return billing.create(user.id(),key,body==null?"{}":json.writeValueAsString(body));
    }
    @PostMapping("/api/billing/orders/{code}/check")
    public Map<String,Object> check(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable long code) {
        return billing.check(user.id(),code);
    }
    @PostMapping("/api/billing/payos/webhook")
    public Map<String,Boolean> webhook(HttpServletRequest request) throws java.io.IOException {
        byte[] bytes = request.getInputStream().readNBytes(65537);
        if (bytes.length>65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Thông báo thanh toán quá lớn.");
        JsonNode body;
        try { body=json.readTree(bytes); }
        catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Thông báo thanh toán không hợp lệ."); }
        if (body==null || !body.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Thông báo thanh toán không hợp lệ.");
        billing.webhook(body);
        return Map.of("success",true);
    }
}
