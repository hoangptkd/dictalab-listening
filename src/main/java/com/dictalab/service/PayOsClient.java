package com.dictalab.service;

import com.dictalab.config.BillingProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class PayOsClient {
    private final BillingProperties config;
    private final ObjectMapper json;
    private final URI origin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @org.springframework.beans.factory.annotation.Autowired
    public PayOsClient(BillingProperties config, ObjectMapper json) { this(config,json,URI.create("https://api-merchant.payos.vn")); }
    PayOsClient(BillingProperties config,ObjectMapper json,URI origin) { this.config=config;this.json=json;this.origin=origin; }

    public JsonNode create(long code, int amount, long expiresAt) {
        if (!config.enabled()) throw unavailable();
        String destination = config.publicBaseUrl() + "/account?orderCode=" + code;
        var body = new TreeMap<String,Object>();
        body.put("orderCode", code); body.put("amount", amount); body.put("description", "DICTALAB");
        body.put("returnUrl", destination); body.put("cancelUrl", destination);
        body.put("signature", sign("amount=" + amount + "&cancelUrl=" + destination
                + "&description=DICTALAB&orderCode=" + code + "&returnUrl=" + destination));
        body.put("expiredAt", expiresAt);
        return request("POST", "/v2/payment-requests", json.writeValueAsString(body));
    }

    public JsonNode lookup(long code) { return request("GET", "/v2/payment-requests/" + code, null); }

    private JsonNode request(String method, String path, String body) {
        if (!config.enabled()) throw unavailable();
        var request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(12)).header("x-client-id", config.clientId())
                .header("x-api-key", config.apiKey()).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = http.send(request, ignored -> BoundedProviderBody.bytes(128 * 1024));
            if(response.statusCode()==429) {
                int delay=60;
                try { delay=Math.max(1,Math.min(900,Integer.parseInt(response.headers().firstValue("Retry-After").orElse("60")))); }
                catch(NumberFormatException ignored) {}
                throw new RateLimited(delay);
            }
            if (response.statusCode() != 200) throw unavailable();
            var payload = json.readTree(response.body());
            if (!"00".equals(payload.path("code").asText()) || !validSignature(payload.path("data"), payload.path("signature").asText()))
                throw unavailable();
            return payload.path("data");
        } catch (RateLimited limited) { throw limited;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception failure) { throw unavailable(); }
    }

    public boolean validSignature(JsonNode data, String signature) {
        if (!config.enabled() || !data.isObject() || signature == null || !signature.matches("[a-fA-F0-9]{64}")) return false;
        var sorted = new TreeMap<String,JsonNode>();
        data.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        String canonical = sorted.entrySet().stream().map(entry -> entry.getKey() + "=" + value(entry.getValue()))
                .collect(Collectors.joining("&"));
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(canonical)), HexFormat.of().parseHex(signature));
    }

    private String value(JsonNode node) {
        if (node.isObject() || node.isArray()) return json.writeValueAsString(sortedValue(node));
        if (node.isNull() || "null".equals(node.asText()) || "undefined".equals(node.asText())) return "";
        return node.asText();
    }

    private Object sortedValue(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String,Object>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), sortedValue(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            var values = new java.util.ArrayList<Object>();
            node.forEach(item -> values.add(sortedValue(item)));
            return values;
        }
        return json.convertValue(node, Object.class);
    }

    String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.checksumKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Cannot sign payOS data"); }
    }

    static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Chưa thể kết nối payOS. Đơn hàng được giữ lại; vui lòng kiểm tra lại trước khi tạo đơn mới.");
    }
    public static class RateLimited extends ResponseStatusException {
        private final int retryAfter;
        public RateLimited(int retryAfter) { super(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_PROVIDER_RATE_LIMITED");this.retryAfter=retryAfter; }
        public int retryAfter() { return retryAfter; }
    }
}
