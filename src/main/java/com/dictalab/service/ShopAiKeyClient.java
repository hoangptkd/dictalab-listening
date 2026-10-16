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
    private Duration assessmentTimeout=Duration.ofSeconds(60);
    private Duration generationTimeout=Duration.ofSeconds(30);

    @org.springframework.beans.factory.annotation.Autowired
    void configureTimeouts(@Value("${dictalab.shopaikey.assessment-timeout-seconds:25}") int assessment,
                           @Value("${dictalab.shopaikey.generation-timeout-seconds:25}") int generation) {
        if (assessment<1 || assessment>60 || generation<1 || generation>30)
            throw new IllegalArgumentException("Invalid provider timeout");
        assessmentTimeout=Duration.ofSeconds(assessment);
        generationTimeout=Duration.ofSeconds(generation);
    }

    public static final class Failure extends IllegalStateException {
        private final String reason;
        Failure(String reason,String message) { super(message); this.reason=reason; }
        public String reason() { return reason; }
    }

    static String reason(RuntimeException failure) {
        if (failure instanceof Failure provider) return provider.reason();
        if (failure instanceof AiUsageBudget.Rejected) return "BUDGET_REJECTED";
        if (failure instanceof ProviderGuard.Unavailable) return "ADMISSION_OR_RATE_LIMIT";
        return "VALIDATION_OR_INTERNAL";
    }

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
        return requestJson(instruction, input, model, assessmentTimeout);
    }

    public JsonNode completeJson(String instruction, String input, String requestModel) {
        // Bound conversation generation/repair independently of the slower assessment requests.
        return requestJson(instruction, input, requestModel, generationTimeout);
    }

    public JsonNode completeJson(String instruction, String input, String requestModel, Duration remaining) {
        if (remaining == null) throw new IllegalArgumentException("Missing request deadline");
        if (remaining.toMillis() < 1) throw new Failure("TIMEOUT", "Đã hết thời gian tạo câu nghe. Phiên vẫn được lưu để thử lại.");
        return requestJson(instruction, input, requestModel,
                remaining.compareTo(generationTimeout) < 0 ? remaining : generationTimeout);
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
                throw new Failure(exception instanceof java.util.concurrent.TimeoutException
                        || exception.getCause() instanceof java.net.http.HttpTimeoutException ? "TIMEOUT" : "TRANSPORT",
                        "Không thể đọc phản hồi ShopAIKey trong thời hạn.");
            } finally { pending.cancel(true); }
            if (response.statusCode() != 200) {
                if(response.statusCode()==429 || response.statusCode()>=500)permit.failure();
                if(response.statusCode()==429){
                    int delay=ProviderGuard.retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now());
                    if(delay>0) throw new ProviderGuard.Unavailable("ShopAIKey trả HTTP 429",delay);
                }
                throw new Failure("HTTP_"+response.statusCode(),"ShopAIKey trả HTTP " + response.statusCode());
            }
            JsonNode choice = json.readTree(response.body()).path("choices").path(0);
            if ("length".equals(choice.path("finish_reason").asText())) {
                throw new Failure("OUTPUT_TRUNCATED","Phản hồi AI vượt giới hạn nội dung.");
            }
            JsonNode answer = choice.path("message").path("content");
            if (!answer.isTextual() || answer.asText().isBlank() || answer.asText().length() > 12_000) {
                throw new Failure("INVALID_CONTENT","ShopAIKey không trả nội dung hợp lệ.");
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
            throw new Failure("INVALID_JSON","ShopAIKey JSON không hợp lệ.");
        }
        }
    }
}
