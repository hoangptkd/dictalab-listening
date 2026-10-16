package com.dictalab.service;

import com.dictalab.config.BillingProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BillingService {
    private record Reservation(Map<String,Object> order, boolean fresh) {}
    private final JdbcTemplate db;
    private final PayOsClient payos;
    private final BillingProperties config;
    private final PremiumAccessService access;
    private final TransactionTemplate transaction;

    public BillingService(JdbcTemplate db, PayOsClient payos, BillingProperties config,
                          PremiumAccessService access, PlatformTransactionManager manager) {
        this.db=db; this.payos=payos; this.config=config; this.access=access;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    public List<Map<String,Object>> orders(long userId) {
        return db.queryForList("""
                SELECT order_code,amount_vnd,duration_days,status,checkout_url,created_at,expires_at,paid_at
                FROM payment_orders WHERE user_id=? ORDER BY created_at DESC LIMIT 30
                """, userId).stream().map(this::view).toList();
    }

    public Map<String,Object> create(long userId) {
        requireEnabled();
        Reservation reservation = transaction.execute(status -> {
            access.lockUser(userId);
            var pending = db.queryForList("SELECT * FROM payment_orders WHERE user_id=? AND status IN ('CREATING','PENDING') AND expires_at>? ORDER BY created_at DESC",
                    userId, Timestamp.from(Instant.now()));
            if (!pending.isEmpty()) return new Reservation(pending.get(0),false);
            var keys = new GeneratedKeyHolder();
            db.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO payment_orders(user_id,amount_vnd,duration_days,status,created_at,expires_at)
                        VALUES (?,?,?,'CREATING',?,?)
                        """, new String[]{"order_code"});
                statement.setLong(1,userId); statement.setInt(2,config.priceVnd()); statement.setInt(3,config.durationDays());
                statement.setTimestamp(4,Timestamp.from(Instant.now()));
                statement.setTimestamp(5,Timestamp.from(Instant.now().plus(15,ChronoUnit.MINUTES)));
                return statement;
            },keys);
            return new Reservation(find(keys.getKey().longValue(),userId),true);
        });
        Map<String,Object> order = reservation.order();
        long code = ((Number)order.get("order_code")).longValue();
        if ("PENDING".equals(order.get("status"))) return view(order);
        // Never retry an uncertain create: query the same order code to recover its outcome.
        if (!reservation.fresh())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Đơn đang chờ xác minh với payOS. Hãy bấm Kiểm tra thanh toán.");
        JsonNode result = payos.create(code,((Number)order.get("amount_vnd")).intValue(),PremiumAccessService.instant(order.get("expires_at")).getEpochSecond());
        if (!result.path("orderCode").isIntegralNumber() || result.path("orderCode").asLong()!=code
                || result.path("amount").asInt()!=((Number)order.get("amount_vnd")).intValue()
                || !"VND".equals(result.path("currency").asText()) || !validCheckout(result.path("checkoutUrl").asText())
                || !result.path("paymentLinkId").asText().matches("[a-zA-Z0-9-]{1,64}")) throw PayOsClient.unavailable();
        transaction.executeWithoutResult(status -> {
            access.lockUser(userId);
            db.update("UPDATE payment_orders SET payment_link_id=?,checkout_url=?,status='PENDING' WHERE order_code=? AND status='CREATING'",
                    result.path("paymentLinkId").asText(),result.path("checkoutUrl").asText(),code);
        });
        return view(find(code,userId));
    }

    public Map<String,Object> check(long userId,long code) {
        requireEnabled();
        var order = find(code,userId);
        if ("PAID".equals(order.get("status"))) return view(order);
        var result = payos.lookup(code);
        if (result.path("orderCode").asLong()!=code || result.path("amount").asInt()!=((Number)order.get("amount_vnd")).intValue()
                || !result.path("id").asText().matches("[a-zA-Z0-9-]{1,64}")) throw PayOsClient.unavailable();
        if (order.get("payment_link_id") != null && !order.get("payment_link_id").equals(result.path("id").asText())) throw PayOsClient.unavailable();
        if ("PAID".equals(result.path("status").asText()) && result.path("amountPaid").asLong() == ((Number)order.get("amount_vnd")).longValue()) {
            settle(code,result.path("id").asText(),((Number)order.get("amount_vnd")).intValue(),"link:"+result.path("id").asText());
        } else if (List.of("CANCELLED","EXPIRED").contains(result.path("status").asText())) {
            transaction.executeWithoutResult(status -> {
                access.lockUser(userId);
                db.update("UPDATE payment_orders SET status=? WHERE order_code=? AND status<>'PAID'",result.path("status").asText(),code);
            });
        } else if ("PENDING".equals(result.path("status").asText())) {
            transaction.executeWithoutResult(status -> {
                access.lockUser(userId);
                db.update("UPDATE payment_orders SET payment_link_id=?,checkout_url=?,status='PENDING' WHERE order_code=? AND status='CREATING'",
                        result.path("id").asText(),"https://pay.payos.vn/web/"+result.path("id").asText(),code);
            });
        }
        return view(find(code,userId));
    }

    public void webhook(JsonNode payload) {
        requireEnabled();
        JsonNode data = payload.path("data");
        if (!payos.validSignature(data,payload.path("signature").asText()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Chữ ký thanh toán không hợp lệ.");
        if (!payload.path("success").asBoolean() || !"00".equals(payload.path("code").asText()) || !"00".equals(data.path("code").asText())) return;
        if (!data.path("orderCode").isIntegralNumber() || !data.path("amount").isIntegralNumber()
                || !"VND".equals(data.path("currency").asText()) || data.path("reference").asText().isBlank()
                || data.path("reference").asText().length()>150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dữ liệu thanh toán không hợp lệ.");
        // payOS sends a signed sample while confirming the webhook URL. Unknown orders never grant access.
        if (db.queryForObject("SELECT COUNT(*) FROM payment_orders WHERE order_code=?",Integer.class,data.path("orderCode").asLong())==0) return;
        settle(data.path("orderCode").asLong(),data.path("paymentLinkId").asText(),data.path("amount").asLong(),data.path("reference").asText());
    }

    private void settle(long code,String link,long amount,String reference) {
        transaction.executeWithoutResult(status -> {
            Long userId = db.queryForObject("SELECT user_id FROM payment_orders WHERE order_code=?",Long.class,code);
            access.lockUser(userId);
            var order = find(code,userId);
            if ("PAID".equals(order.get("status"))) return;
            if (order.get("payment_link_id") == null && !"link:".concat(link).equals(reference))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Đơn đang đồng bộ. Vui lòng gửi lại thông báo.");
            if (order.get("payment_link_id") != null && !link.equals(order.get("payment_link_id"))
                    || amount != ((Number)order.get("amount_vnd")).longValue())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Giao dịch không khớp đơn hàng.");
            var account = db.queryForList("SELECT premium_until FROM premium_accounts WHERE user_id=?",userId);
            Instant now = Instant.now();
            Instant previous = account.isEmpty()?now:PremiumAccessService.instant(account.get(0).get("premium_until"));
            Instant until = (previous.isAfter(now)?previous:now).plus(((Number)order.get("duration_days")).longValue(),ChronoUnit.DAYS);
            if (account.isEmpty()) db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)",userId,Timestamp.from(until));
            else db.update("UPDATE premium_accounts SET premium_until=? WHERE user_id=?",Timestamp.from(until),userId);
            db.update("UPDATE payment_orders SET status='PAID',paid_at=?,payment_reference=?,payment_link_id=? WHERE order_code=?",
                    Timestamp.from(now),reference,link,code);
            db.update("INSERT INTO account_audit(subject_id,action,detail) VALUES (?,'PREMIUM_PAYMENT',?)",
                    userId,"payOS order="+code+", days="+order.get("duration_days")+", until="+until);
        });
    }

    private Map<String,Object> find(long code,long userId) {
        var rows = db.queryForList("SELECT * FROM payment_orders WHERE order_code=? AND user_id=?",code,userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy đơn hàng.");
        return rows.get(0);
    }
    private Map<String,Object> view(Map<String,Object> row) {
        var result = new HashMap<String,Object>();
        result.put("orderCode",row.get("order_code")); result.put("amountVnd",row.get("amount_vnd"));
        result.put("durationDays",row.get("duration_days")); result.put("status",row.get("status"));
        result.put("checkoutUrl",row.get("checkout_url"));
        for (var field:List.of("created_at","expires_at","paid_at")) {
            String name = switch(field) {case "created_at" -> "createdAt"; case "expires_at" -> "expiresAt"; default -> "paidAt";};
            result.put(name,row.get(field)==null?null:PremiumAccessService.instant(row.get(field)));
        }
        if (!"PAID".equals(row.get("status")) && PremiumAccessService.instant(row.get("expires_at")).isBefore(Instant.now())) result.put("status","EXPIRED");
        return result;
    }
    private boolean validCheckout(String value) {
        try { URI uri=URI.create(value); return "https".equals(uri.getScheme()) && "pay.payos.vn".equals(uri.getHost())
                && uri.getUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443); }
        catch (IllegalArgumentException failure) { return false; }
    }
    private void requireEnabled() {
        if (!config.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Thanh toán Premium đang được thiết lập. Bạn vẫn có thể học bình thường.");
    }
}
