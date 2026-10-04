package com.dictalab.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class ShopAiKeyClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final String url;
    private final ProviderGuard guard;

    @org.springframework.beans.factory.annotation.Autowired
    public ShopAiKeyClient(ObjectMapper json,
                           @Value("${dictalab.shopaikey.api-key:}") String key,
                           @Value("${dictalab.shopaikey.model:claude-sonnet-4-5}") String model,
                           @Value("${dictalab.shopaikey.url:https://api.shopaikey.com/v1/chat/completions}") String url,
                           @Value("${dictalab.shopaikey.concurrency:8}") int concurrency,
                           io.micrometer.core.instrument.MeterRegistry metrics,
                           io.micrometer.observation.ObservationRegistry observations,
                           @Value("${dictalab.provider.circuit-failures:5}") int circuitFailures,
                           @Value("${dictalab.provider.circuit-open-seconds:30}") int circuitOpenSeconds) {
        this.json = json;
        this.key = key;
        this.model = model;
        this.url = url;
        this.guard=new ProviderGuard("shopaikey",concurrency,metrics,observations,circuitFailures,Duration.ofSeconds(circuitOpenSeconds));
    }

    public ShopAiKeyClient(ObjectMapper json,String key,String model,String url){
        this(json,key,model,url,8,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                io.micrometer.observation.ObservationRegistry.NOOP,5,30);
    }

    public boolean configured() {
        return !key.isBlank();
    }

    public String model() { return model; }

    public JsonNode completeJson(String instruction, String input) {
        return requestJson(instruction, input, model, Duration.ofSeconds(60));
    }

    public JsonNode completeJson(String instruction, String input, String requestModel) {
        // Bound conversation generation/repair independently of the slower assessment requests.
        return requestJson(instruction, input, requestModel, Duration.ofSeconds(30));
    }

    private JsonNode requestJson(String instruction, String input, String requestModel, Duration timeout) {
        if (!configured()) throw new IllegalStateException("ShopAIKey chưa được cấu hình trên máy chủ.");
        if (requestModel == null || requestModel.isBlank()) throw new IllegalArgumentException("Missing ShopAIKey model");
        try (var permit=guard.enter()) {
          try {
            String body = json.writeValueAsString(Map.of(
                    "model", requestModel, "temperature", 0, "max_tokens", 2200,
                    "messages", List.of(Map.of("role", "system", "content", instruction),
                            Map.of("role", "user", "content", input))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var pending = http.sendAsync(request, info -> BoundedProviderBody.bytes(100_000));
            HttpResponse<byte[]> response;
            try {
                // HttpRequest.timeout alone must not leave a slow response body occupying a permit.
                response = pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                permit.failure();
                throw new IllegalStateException("Không thể đọc phản hồi ShopAIKey trong thời hạn.", exception);
            } finally { pending.cancel(true); }
            if (response.statusCode() != 200) {
                if(response.statusCode()==429 || response.statusCode()>=500)permit.failure();
                if(response.statusCode()==429){
                    int delay=ProviderGuard.retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now());
                    if(delay>0) throw new ProviderGuard.Unavailable("ShopAIKey trả HTTP 429",delay);
                }
                throw new IllegalStateException("ShopAIKey trả HTTP " + response.statusCode());
            }
            JsonNode answer = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!answer.isTextual() || answer.asText().isBlank() || answer.asText().length() > 12_000) {
                throw new IllegalStateException("ShopAIKey không trả nội dung hợp lệ.");
            }
            String content = answer.asText().trim();
            if (content.startsWith("```")) {
                int firstLine = content.indexOf('\n');
                int closingFence = content.lastIndexOf("```");
                if (firstLine >= 0 && closingFence > firstLine) {
                    content = content.substring(firstLine + 1, closingFence).trim();
                }
            }
            int objectStart = content.indexOf('{');
            int objectEnd = content.lastIndexOf('}');
            if (objectStart >= 0 && objectEnd >= objectStart) {
                content = content.substring(objectStart, objectEnd + 1);
            }
            JsonNode result = json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(content);
            if (!result.isObject()) throw new IllegalStateException("ShopAIKey JSON object missing");
            permit.success();
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yêu cầu ShopAIKey bị gián đoạn.", exception);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("ShopAIKey JSON không hợp lệ.", exception);
        }
        }
    }
}
