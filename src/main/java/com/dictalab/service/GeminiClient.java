package com.dictalab.service;

import com.dictalab.config.GeminiProviderProperties;
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
import java.util.concurrent.atomic.AtomicInteger;

/** Gemini text client using Google's OpenAI-compatible chat endpoint; keeps an ephemeral Ollama path for local development. */
@Service
public class GeminiClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final String url;
    private final ProviderGuard guard;
    private final GeminiProviderProperties providerProperties;
    private final GeminiQuotaCoordinator quota;
    private final AtomicInteger nextProvider = new AtomicInteger();
    private Duration assessmentTimeout=Duration.ofSeconds(60);
    private Duration generationTimeout=Duration.ofSeconds(30);
    private boolean local;
    private String localBaseUrl = "http://127.0.0.1:11434", localModel = "qwen3.5:9b";

    @org.springframework.beans.factory.annotation.Autowired
    void configureLocal(@Value("${dictalab.ai.platform.text-provider:gemini}") String provider,
                        @Value("${dictalab.conversation.generation.local-base-url:http://127.0.0.1:11434}") String baseUrl,
                        @Value("${dictalab.conversation.generation.local-model:qwen3.5:9b}") String model) {
        var validated = new com.dictalab.config.ConversationGenerationProperties("ollama", baseUrl, model, 60);
        if (!java.util.Set.of("ollama", "gemini").contains(provider)) throw new IllegalArgumentException("Invalid AI provider");
        local = provider.equals("ollama"); localBaseUrl = validated.localBaseUrl(); localModel = validated.localModel();
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureTimeouts(@Value("${dictalab.gemini.assessment-timeout-seconds:25}") int assessment,
                           @Value("${dictalab.gemini.generation-timeout-seconds:25}") int generation) {
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
    public GeminiClient(ObjectMapper json,
                           @Value("${dictalab.gemini.api-key:}") String key,
                           @Value("${dictalab.gemini.model:gemini-3.5-flash-lite}") String model,
                           @Value("${dictalab.gemini.url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}") String url,
                           @Value("${dictalab.gemini.concurrency:8}") int concurrency,
                           io.micrometer.core.instrument.MeterRegistry metrics,
                           io.micrometer.observation.ObservationRegistry observations,
                           @Value("${dictalab.provider.circuit-failures:5}") int circuitFailures,
                           @Value("${dictalab.provider.circuit-open-seconds:30}") int circuitOpenSeconds,
                           GeminiProviderProperties providerProperties,
                           GeminiQuotaCoordinator quota) {
        this.json = json;
        this.key = key;
        this.model = model;
        this.url = url;
        this.guard=new ProviderGuard("gemini",concurrency,metrics,observations,circuitFailures,Duration.ofSeconds(circuitOpenSeconds));
        this.providerProperties = providerProperties;
        this.quota = quota;
    }

    public GeminiClient(ObjectMapper json,String key,String model,String url){
        this(json,key,model,url,8,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                io.micrometer.observation.ObservationRegistry.NOOP,5,30,
                new GeminiProviderProperties(List.of(),0,0,false,"memory"),null);
    }

    public GeminiClient(ObjectMapper json,String key,String model,String url,int concurrency,
                        io.micrometer.core.instrument.MeterRegistry metrics,
                        io.micrometer.observation.ObservationRegistry observations,int circuitFailures,
                        int circuitOpenSeconds) {
        this(json,key,model,url,concurrency,metrics,observations,circuitFailures,circuitOpenSeconds,
                new GeminiProviderProperties(List.of(),0,0,false,"memory"),null);
    }

    public boolean configured() {
        return local || !key.isBlank() || routingEnabled();
    }

    public String model() { return local ? localModel : model; }

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
        if (!configured()) throw new IllegalStateException("Gemini chưa được cấu hình trên máy chủ.");
        if (requestModel == null || requestModel.isBlank()) throw new IllegalArgumentException("Missing Gemini model");
        try (var permit=guard.enter()) {
          try {
            ProviderSelection selection = local ? null : selectProvider(instruction, input, requestModel);
            String targetModel = local ? localModel : selection.model();
            String targetUrl = local ? localBaseUrl + "/api/chat" : selection.url();
            String targetKey = local ? "" : selection.apiKey();
            String body = json.writeValueAsString(local ? Map.of("model", localModel, "stream", false, "think", false,
                    "format", "json", "options", Map.of("temperature", 0, "num_ctx", 8192, "num_predict", 2200),
                    "messages", List.of(Map.of("role", "system", "content", instruction), Map.of("role", "user", "content", input))) : Map.of(
                    "model", targetModel, "temperature", 0, "max_tokens", 2200,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(Map.of("role", "system", "content", instruction),
                            Map.of("role", "user", "content", input))));
            var builder = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!local) builder.header("Authorization", "Bearer " + targetKey);
            HttpRequest request = builder.build();
            var pending = http.sendAsync(request, info -> BoundedProviderBody.bytes(100_000));
            HttpResponse<byte[]> response;
            try {
                // HttpRequest.timeout alone must not leave a slow response body occupying a permit.
                response = pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                if (routingEnabled()) recordOutcome(selection, "failure", 0); else permit.failure();
                throw new Failure(exception instanceof java.util.concurrent.TimeoutException
                        || exception.getCause() instanceof java.net.http.HttpTimeoutException ? "TIMEOUT" : "TRANSPORT",
                        "Không thể đọc phản hồi Gemini trong thời hạn.");
            } finally { pending.cancel(true); }
            if (response.statusCode() != 200) {
                if (routingEnabled() && (response.statusCode()==429 || response.statusCode()>=500)) {
                    int delay = cooldownSeconds(response.headers().firstValue("Retry-After").orElse(null));
                    recordOutcome(selection, response.statusCode()==429 ? "429" : "failure", delay);
                } else if(response.statusCode()==429 || response.statusCode()>=500) permit.failure();
                if(response.statusCode()==429){
                    int delay=ProviderGuard.retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now());
                    if(delay>0) throw new ProviderGuard.Unavailable("Gemini trả HTTP 429",delay);
                }
                throw new Failure("HTTP_"+response.statusCode(),"Gemini trả HTTP " + response.statusCode());
            }
            JsonNode envelope = json.readTree(response.body());
            if (local && (!envelope.path("done").asBoolean() || !"stop".equals(envelope.path("done_reason").asText())))
                throw new Failure("OUTPUT_TRUNCATED", "Incomplete local AI response");
            JsonNode choice = envelope.path("choices").path(0);
            if ("length".equals(choice.path("finish_reason").asText())) {
                throw new Failure("OUTPUT_TRUNCATED","Phản hồi AI vượt giới hạn nội dung.");
            }
            JsonNode answer = (local ? envelope : choice).path("message").path("content");
            if (!answer.isTextual() || answer.asText().isBlank() || answer.asText().length() > 12_000) {
                throw new Failure("INVALID_CONTENT","Gemini không trả nội dung hợp lệ.");
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
            if (!result.isObject()) throw new IllegalStateException("Gemini JSON object missing");
            if (routingEnabled()) recordOutcome(selection, "success", 0);
            permit.success();
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yêu cầu Gemini bị gián đoạn.", exception);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new Failure("INVALID_JSON","Gemini JSON không hợp lệ.");
        }
        }
    }

    private boolean routingEnabled() {
        return !local && providerProperties != null && providerProperties.quotaEnabled()
                && !providerProperties.enabledProviders().isEmpty() && quota != null;
    }

    private ProviderSelection selectProvider(String instruction, String input, String requestModel) {
        if (!routingEnabled()) return new ProviderSelection(null, requestModel, url, key);
        var providers = providerProperties.enabledProviders();
        int start = Math.floorMod(nextProvider.getAndIncrement(), providers.size());
        // Byte-based upper bound is deliberately conservative for Vietnamese input.
        int estimatedTokens = Math.addExact(Math.addExact(instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length), 2300);
        long retryMs = Long.MAX_VALUE;
        for (int offset = 0; offset < providers.size(); offset++) {
            var provider = providers.get((start + offset) % providers.size());
            String selectedModel = requestModel.replaceFirst("^models/", "");
            if (!provider.model().equals(selectedModel)) continue;
            var admission = quota.reserve(provider.id(), selectedModel, estimatedTokens);
            if (admission.allowed()) return new ProviderSelection(provider.id(), selectedModel, provider.url(), provider.apiKey());
            retryMs = Math.min(retryMs, admission.retryAfterMs());
        }
        int retry = retryMs == Long.MAX_VALUE ? 60 : (int)Math.min(86400, Math.max(1, (retryMs + 999) / 1000));
        throw new ProviderGuard.Unavailable("Gemini đã hết quota tạm thời.", retry, true);
    }

    private void recordOutcome(ProviderSelection selection, String outcome, int delay) {
        try { quota.outcome(selection.id(), selection.model(), outcome, delay); }
        catch (RuntimeException unavailable) {
            // Preserve the original result: a Redis outage after send is never retry-safe.
            org.slf4j.LoggerFactory.getLogger(GeminiClient.class).warn("Gemini quota outcome persistence unavailable");
        }
    }

    private static int cooldownSeconds(String header) {
        try {
            if (header != null && header.matches("[0-9]+")) return (int)Math.min(86400, Math.max(1, Long.parseLong(header)));
            if (header != null) return (int)Math.min(86400, Math.max(1, Duration.between(java.time.Instant.now(),
                    java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()));
        } catch (RuntimeException invalid) { /* Use bounded fallback. */ }
        return 60;
    }

    private record ProviderSelection(String id, String model, String url, String apiKey) {
        @Override public String toString() { return "ProviderSelection[id=" + id + ", model=" + model + "]"; }
    }
}
