package com.dictalab.service;

import com.dictalab.config.BillingProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.time.*;
import java.util.*;
import static com.dictalab.service.PaymentData.*;

@Service
public class BillingService {
    private record Reservation(Map<String,Object> order,boolean fresh) {}
    private final JdbcTemplate db;
    private final PayOsClient payos;
    private final BillingProperties config;
    private final PremiumAccessService access;
    private final PaymentOperations operations;
    private final Clock clock;
    private final TransactionTemplate tx;
    public BillingService(JdbcTemplate db,PayOsClient payos,BillingProperties config,PremiumAccessService access,
            PlatformTransactionManager manager,PaymentOperations operations,Clock paymentClock) {
        this.db=db;this.payos=payos;this.config=config;this.access=access;this.operations=operations;this.clock=paymentClock;
        tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    public List<Map<String,Object>> orders(long user) {
        return rows(db,"SELECT * FROM payment_orders WHERE user_id=? ORDER BY created_at DESC,order_code DESC LIMIT 30",user).stream().map(this::view).toList();
    }
    public Map<String,Object> create(long user) { return create(user,null,"{}"); }
    public Map<String,Object> create(long user,String key,String requestBody) {
        requireEnabled();
        if(!operations.checkoutEnabled()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_CHECKOUT_PAUSED");
        if(key!=null && !key.matches("[A-Za-z0-9_-]{16,100}")) throw error(HttpStatus.BAD_REQUEST,"PAYMENT_IDEMPOTENCY_KEY_INVALID");
        if(requestBody.length()>4096) throw error(HttpStatus.PAYLOAD_TOO_LARGE,"PAYMENT_REQUEST_TOO_LARGE");
        Reservation reserved=tx.execute(status->{
            access.lockUser(user);
            if(key!=null) {
                var prior=rows(db,"SELECT * FROM payment_idempotency WHERE actor_id=? AND operation='CREATE_ORDER' AND request_key=?",user,key);
                if(!prior.isEmpty()) {
                    if(!hash(requestBody).equals(prior.get(0).get("request_hash"))) throw error(HttpStatus.CONFLICT,"PAYMENT_IDEMPOTENCY_CONFLICT");
                    return new Reservation(find(Long.parseLong((String)prior.get(0).get("resource_id")),user),false);
                }
            }
            var pending=rows(db,"SELECT * FROM payment_orders WHERE user_id=? AND (status='CREATING' OR (status='PENDING' AND expires_at>?)) ORDER BY created_at DESC",user,ts(clock.instant()));
            Map<String,Object> order;boolean fresh=pending.isEmpty();
            if(!fresh) order=pending.get(0);
            else {
                var keys=new GeneratedKeyHolder();
                db.update(connection->{
                    var p=connection.prepareStatement("INSERT INTO payment_orders(user_id,amount_vnd,duration_days,status,created_at,expires_at,next_check_at,policy_version,price_version,product_code) VALUES (?,?,?,'CREATING',?,?,?,?,?,?)",new String[]{"order_code"});
                    p.setLong(1,user);p.setLong(2,config.priceVnd());p.setInt(3,config.durationDays());
                    p.setTimestamp(4,ts(clock.instant()));p.setTimestamp(5,ts(clock.instant().plusSeconds(900)));
                    p.setTimestamp(6,ts(clock.instant().plusSeconds(60)));p.setString(7,operations.policyVersion());
                    p.setString(8,"VND-"+config.priceVnd()+"-"+config.durationDays()+"D");p.setString(9,"PREMIUM_"+config.durationDays()+"D");return p;
                },keys);
                order=find(keys.getKey().longValue(),user);
                if(number(order,"order_code")>9_007_199_254_740_991L) throw error(HttpStatus.CONFLICT,"PAYMENT_ORDER_RANGE_EXHAUSTED");
                operations.audit(user,"ORDER_RESERVED",order.get("order_code").toString(),"Server price and policy snapshot");
            }
            if(key!=null) db.update("INSERT INTO payment_idempotency(actor_id,operation,request_key,request_hash,resource_id,created_at) VALUES (?,'CREATE_ORDER',?,?,?,?)",
                    user,key,hash(requestBody),order.get("order_code").toString(),ts(clock.instant()));
            return new Reservation(order,fresh);
        });
        var order=reserved.order();long code=number(order,"order_code");
        if(!reserved.fresh()) {
            if(!"CREATING".equals(order.get("status"))) return view(order);
            throw error(HttpStatus.CONFLICT,"PAYMENT_CREATE_OUTCOME_UNKNOWN");
        }
        JsonNode result=payos.create(code,Math.toIntExact(number(order,"amount_vnd")),at(order.get("expires_at")).getEpochSecond());
        if(!result.path("orderCode").isIntegralNumber() || result.path("orderCode").asLong()!=code
                || money(result.path("amount"))!=number(order,"amount_vnd") || !"VND".equals(result.path("currency").asText())
                || !validCheckout(result.path("checkoutUrl").asText()) || !result.path("paymentLinkId").asText().matches("[A-Za-z0-9-]{1,64}")) throw PayOsClient.unavailable();
        tx.executeWithoutResult(s->{
            access.lockUser(user);
            db.update("UPDATE payment_orders SET payment_link_id=?,checkout_url=?,status='PENDING',edit_version=edit_version+1 WHERE order_code=? AND status='CREATING'",
                    result.path("paymentLinkId").asText(),result.path("checkoutUrl").asText(),code);
        });return view(find(code,user));
    }
    public Map<String,Object> check(long user,long code) {
        requireEnabled();var order=find(code,user);
        if("PAID".equals(order.get("status"))) return view(order);
        operations.lookup(code,payos.lookup(code),id(),number(order,"edit_version"));
        return view(find(code,user));
    }
    public void webhook(JsonNode payload) {
        requireEnabled();
        if(!payos.validSignature(payload.path("data"),payload.path("signature").asText())) {
            operations.rejectedWebhook();
            throw error(HttpStatus.UNAUTHORIZED,"PAYMENT_SIGNATURE_INVALID");
        }
        String event=operations.receive(payload);
        // Fast path after durable intake; the leased worker recovers any interruption.
        operations.process(event);
    }
    private Map<String,Object> find(long code,long user) { return one(db,"SELECT * FROM payment_orders WHERE order_code=? AND user_id=?",code,user); }
    private Map<String,Object> view(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        result.put("orderCode",row.get("order_code"));result.put("amountVnd",row.get("amount_vnd"));result.put("durationDays",row.get("duration_days"));
        String state=(String)row.get("status");
        if("PENDING".equals(state) && at(row.get("expires_at")).isBefore(clock.instant())) state="EXPIRED";
        result.put("status",state);result.put("checkoutUrl",row.get("checkout_url"));
        result.put("productCode",row.get("product_code"));result.put("priceVersion",row.get("price_version"));result.put("policyVersion",row.get("policy_version"));result.put("currency",row.get("currency"));
        for(var f:List.of("created_at","expires_at","paid_at")) result.put(switch(f){case "created_at"->"createdAt";case "expires_at"->"expiresAt";default->"paidAt";},row.get(f)==null?null:at(row.get(f)));
        return result;
    }
    private boolean validCheckout(String value) {
        try { URI uri=URI.create(value);return "https".equals(uri.getScheme()) && "pay.payos.vn".equals(uri.getHost()) && uri.getUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443); }
        catch(IllegalArgumentException failure) { return false; }
    }
    private void requireEnabled() { if(!config.enabled()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_NOT_CONFIGURED"); }
}
