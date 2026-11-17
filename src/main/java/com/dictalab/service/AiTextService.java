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

/** Shared text boundary. Business services never construct provider HTTP requests. */
@Service
public class AiTextService {
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
    private String cloudPlatform = "gemini";
    private AiProviderRegistry registry;
    private AiPlatformClient platform;
    private boolean remoteText;
    private String remoteModel;

    private Map<String, AiTextProvider> adapters = builtins();
    private com.dictalab.config.AiTextProperties textProperties = new com.dictalab.config.AiTextProperties("{}");
    public static Map<String, AiTextProvider> builtins() {
        return java.util.stream.Stream.of(new GeminiTextProvider(), new DeepSeekTextProvider(),
            new OpenAiCompatibleTextProvider(), new OllamaTextProvider()).collect(java.util.stream.Collectors.toUnmodifiableMap(AiTextProvider::id, p -> p));
    }
    @org.springframework.beans.factory.annotation.Autowired
    void configureProviders(List<AiTextProvider> providers, com.dictalab.config.AiTextProperties properties) {
        var registered = new java.util.HashMap<String, AiTextProvider>();
        for (var adapter : providers) {
            if (!adapter.id().matches("[a-z][a-z0-9_]{0,31}") || registered.putIfAbsent(adapter.id(), adapter) != null)
                throw new IllegalArgumentException("Invalid or duplicate AI provider registration");
        }
        this.adapters = Map.copyOf(registered); this.textProperties = properties;
    }
    private AiTextProvider adapter(String provider) {
        var result = adapters.get(provider);
        if (result == null) throw new Failure("UNREGISTERED_PROVIDER", "AI provider is not registered for direct execution");
        return result;
    }
    public String provider() { return cloudPlatform; }
    public String capabilityConfiguration() { return textProperties.modelProfilesJson(); }
    public List<Class<?>> providerTypes() { return adapters.values().stream().map(p -> (Class<?>)p.getClass()).sorted(java.util.Comparator.comparing(Class::getName)).collect(java.util.stream.Collectors.toList()); }
    public record Message(String role, String content) {
        public Message {
            if (role == null || !java.util.Set.of("system", "user", "assistant").contains(role) || content == null || content.isBlank())
                throw new IllegalArgumentException("Invalid AI message");
        }
    }
    public record Request(java.util.UUID requestId, String purpose, String profile, String model,
                          List<Message> messages, JsonNode schema, String outputMode, Double temperature,
                          int maxOutputTokens, Duration remaining, int contextTokens, String keepAlive) {
        public Request {
            if (requestId == null || purpose == null || !purpose.matches("[a-z][a-z0-9_]{0,63}")
                || profile == null || !java.util.Set.of("default", "conversation_local", "coach_local").contains(profile)
                || model == null || !model.matches("[a-zA-Z0-9_.:/-]{1,128}")
                || messages == null || messages.isEmpty() || messages.size() > 32 || messages.stream().anyMatch(java.util.Objects::isNull)
                || outputMode == null || !java.util.Set.of("json_object", "json_schema").contains(outputMode)
                || outputMode.equals("json_schema") && (schema == null || !schema.isObject())
                || temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)
                || maxOutputTokens < 1 || maxOutputTokens > 32768 || remaining == null || remaining.toMillis() < 1
                || remaining.compareTo(Duration.ofSeconds(600)) > 0 || contextTokens < 512 || contextTokens > 131072
                || keepAlive != null && !keepAlive.equals("5m")) throw new IllegalArgumentException("Invalid AI text request");
            messages = List.copyOf(messages);
            if (messages.stream().mapToLong(m -> m.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum() > 256000)
                throw new IllegalArgumentException("AI text input exceeds limit");
            schema = schema == null ? null : schema.deepCopy();
        }
        Request withModel(String selected) {
            return new Request(requestId, purpose, profile, selected, messages, schema, outputMode, temperature,
                maxOutputTokens, remaining, contextTokens, keepAlive);
        }
        @Override public String toString() { return "AiTextRequest[id=" + requestId + ", purpose=" + purpose + ", model=" + model + "]"; }
    }
    public record Result(java.util.UUID requestId, JsonNode value, String provider, String requestedModel,
                         String model, String reportedModel, String sourceId, List<JsonNode> fallbacks, JsonNode usage) {
        @Override public String toString() { return "AiTextResult[id=" + requestId + ", provider=" + provider + ", model=" + model + "]"; }
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureTextTransport(AiPlatformClient platform,
            @Value("${dictalab.ai.platform.text-transport:legacy}") String transport,
            @Value("${dictalab.ai.platform.text-model:gemini-3.5-flash-lite}") String textModel) {
        if (!java.util.Set.of("legacy", "python").contains(transport)
                || textModel == null || !textModel.matches("[a-zA-Z0-9_.:/-]{1,128}"))
            throw new IllegalArgumentException("Invalid AI text transport or model");
        if (transport.equals("python") && !platform.textConfigured())
            throw new IllegalArgumentException("AI text transport requires a strong service token");
        this.platform = platform; this.remoteText = transport.equals("python"); this.remoteModel = textModel;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureRegistry(AiProviderRegistry registry) { this.registry = registry; }

    private boolean databaseEnabled() { return registry != null && registry.databaseEnabled(); }
    private GeminiProviderProperties properties() { return databaseEnabled() ? registry.properties(cloudPlatform) : providerProperties; }
    private String localBaseUrl = "http://127.0.0.1:11434", localModel = "qwen3.5:9b";

    @org.springframework.beans.factory.annotation.Autowired
    void configureLocal(@Value("${dictalab.ai.platform.text-provider:gemini}") String provider,
                        @Value("${dictalab.conversation.generation.local-base-url:http://127.0.0.1:11434}") String baseUrl,
                        @Value("${dictalab.conversation.generation.local-model:qwen3.5:9b}") String model) {
        var validated = new com.dictalab.config.ConversationGenerationProperties("ollama", baseUrl, model, 60);
        if (provider == null || !provider.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid AI provider");
        cloudPlatform = provider;
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
        public String outcome() {
            if (java.util.Set.of("TIMEOUT", "TRANSPORT", "AI_SERVICE", "TIMEOUT_OR_TRANSPORT", "INTERRUPTED",
                    "PROVIDER_OUTCOME_UNKNOWN", "ALREADY_ADMITTED").contains(reason)) return "UNKNOWN";
            if (java.util.Set.of("INVALID_JSON", "INVALID_CONTENT", "OUTPUT_TRUNCATED", "INVALID_OUTPUT").contains(reason)) return "RESPONSE_RECEIVED";
            if (reason.startsWith("HTTP_")) return reason.matches("HTTP_4[0-9]{2}") ? "REJECTED" : "UNKNOWN";
            return reason.equals("PROVIDER_REJECTED") ? "REJECTED" : "NOT_SENT";
        }
    }

    static String reason(RuntimeException failure) {
        if (failure instanceof Failure provider) return provider.reason();
        if (failure instanceof AiUsageBudget.Rejected) return "BUDGET_REJECTED";
        if (failure instanceof ProviderGuard.Unavailable) return "ADMISSION_OR_RATE_LIMIT";
        return "VALIDATION_OR_INTERNAL";
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiTextService(ObjectMapper json,
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

    public AiTextService(ObjectMapper json,String key,String model,String url){
        this(json,key,model,url,8,new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                io.micrometer.observation.ObservationRegistry.NOOP,5,30,
                new GeminiProviderProperties(List.of(),0,0,false,"memory"),null);
    }

    public AiTextService(ObjectMapper json,String key,String model,String url,int concurrency,
                        io.micrometer.core.instrument.MeterRegistry metrics,
                        io.micrometer.observation.ObservationRegistry observations,int circuitFailures,
                        int circuitOpenSeconds) {
        this(json,key,model,url,concurrency,metrics,observations,circuitFailures,circuitOpenSeconds,
                new GeminiProviderProperties(List.of(),0,0,false,"memory"),null);
    }

    public boolean configured() {
        if (remoteText) return platform.textConfigured();
        adapter(cloudPlatform);
        return local || (databaseEnabled() || !cloudPlatform.equals("gemini") ? routingEnabled() : !key.isBlank() || routingEnabled());
    }

    public String model() { return remoteText ? remoteModel : local ? localModel : databaseEnabled() ? registry.model(cloudPlatform) : model; }

    public JsonNode completeJson(String instruction, String input) {
        return requestJson(instruction, input, model(), assessmentTimeout);
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


    public Result completeFor(String purpose, String instruction, String input) {
        return complete(new Request(java.util.UUID.randomUUID(), purpose, "default", model(),
            List.of(new Message("system", instruction), new Message("user", input)), null, "json_object", 0.0,
            2200, assessmentTimeout, 8192, null));
    }
    public JsonNode completeJsonFor(String purpose, String instruction, String input) {
        return requestJsonFor(purpose, instruction, input, model(), assessmentTimeout);
    }
    public JsonNode completeJsonFor(String purpose, String instruction, String input, String model) {
        return requestJsonFor(purpose, instruction, input, model, generationTimeout);
    }
    public JsonNode completeJsonFor(String purpose, String instruction, String input, String model, Duration remaining) {
        if (remaining == null) throw new IllegalArgumentException("Missing request deadline");
        if (remaining.toMillis() < 1) throw new Failure("TIMEOUT", "AI deadline exhausted");
        return requestJsonFor(purpose, instruction, input, model, remaining.compareTo(generationTimeout) < 0 ? remaining : generationTimeout);
    }
    private JsonNode requestJsonFor(String purpose, String instruction, String input, String model, Duration timeout) {
        return complete(new Request(java.util.UUID.randomUUID(), purpose, "default", model,
            List.of(new Message("system", instruction), new Message("user", input)), null, "json_object", 0.0,
            2200, timeout, 8192, null)).value();
    }

    private JsonNode requestJson(String instruction, String input, String requestModel, Duration timeout) {
        return complete(new Request(java.util.UUID.randomUUID(), "core_text", "default", requestModel,
            List.of(new Message("system", instruction), new Message("user", input)), null, "json_object", 0.0,
            2200, timeout, 8192, null)).value();
    }

    public Result complete(Request request) {
        if (!request.profile().equals("default")) throw new IllegalArgumentException("Use the configured local workflow for a local AI profile");
        AiCallObservation.begin(request.model());
        String outcome = "UNKNOWN";
        try {
            var result = sendJson(request);
            AiCallObservation.result(result);
            outcome = "SUCCESS";
            return result;
        } catch (RuntimeException error) { outcome = reason(error); throw error;
        } finally { AiCallObservation.end(outcome); }
    }

    private Result sendJson(Request call) {
        String requestModel = call.model(); Duration timeout = call.remaining();
        String instruction = call.messages().stream().map(Message::content).collect(java.util.stream.Collectors.joining("\n"));
        String input = "";
        long deadline = System.nanoTime() + timeout.toNanos();
        var attempted = new java.util.HashSet<String>();
        if (!configured()) throw new IllegalStateException("AI chưa được cấu hình trên máy chủ.");
        if (requestModel == null || requestModel.isBlank()) throw new IllegalArgumentException("Missing AI model");
        try (var permit=guard.enter()) {
          try {
            if (remoteText) {
                try {
                    Result result = platform.completeText(local ? call.withModel(remoteModel) : call);
                    permit.success();
                    return result;
                } catch (ProviderGuard.Unavailable unavailable) {
                    throw unavailable;
                } catch (Failure rejected) {
                    if (!java.util.Set.of("INVALID_REQUEST", "UNREGISTERED_PROVIDER", "INVALID_MODEL", "UNSUPPORTED_CAPABILITY").contains(rejected.reason()))
                        permit.failure();
                    throw rejected;
                } catch (RuntimeException unavailable) {
                    permit.failure();
                    throw new Failure("AI_SERVICE", "AI service did not return a verified text result");
                }
            }
          while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) throw new Failure("TIMEOUT", "AI request deadline exhausted");
            Duration remaining = Duration.ofNanos(remainingNanos);
            ProviderSelection selection = local ? null : selectProvider(instruction, input, requestModel, attempted);
            if (selection != null && selection.id() != null) attempted.add(selection.id());
            String targetModel = local ? localModel : selection.model();
            String targetUrl = local ? localBaseUrl + "/api/chat" : selection.url();
            String targetKey = local ? "" : selection.apiKey();
            remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (remaining.toMillis() < 1) throw new Failure("TIMEOUT", "AI deadline exhausted before send");
            var selectedAdapter = adapter(local ? "ollama" : cloudPlatform);
            var target = new AiTextProvider.Target(targetUrl, targetModel, targetKey, selection == null ? null : selection.id());
            HttpRequest request = selectedAdapter.prepare(json, target, call,
                textProperties.profile(selectedAdapter.id(), targetModel), remaining);
            if (request.bodyPublisher().orElseThrow().contentLength() > 256000)
                throw new IllegalArgumentException("AI text input exceeds limit");
            AiCallObservation.http(local ? "ollama" : selection.id(), targetModel, 0);
            var pending = http.sendAsync(request, info -> BoundedProviderBody.bytes(100_000));
            HttpResponse<byte[]> response;
            try {
                // HttpRequest.timeout alone must not leave a slow response body occupying a permit.
                response = pending.get(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                if (routingEnabled()) recordOutcome(selection, "failure", 0); else permit.failure();
                throw new Failure(exception instanceof java.util.concurrent.TimeoutException
                        || exception.getCause() instanceof java.net.http.HttpTimeoutException ? "TIMEOUT" : "TRANSPORT",
                        "Không thể đọc phản hồi AI trong thời hạn.");
            } finally { pending.cancel(true); }
            AiCallObservation.status(response.statusCode());
            if (response.statusCode() != 200) {
                if (routingEnabled() && (response.statusCode()==429 || response.statusCode()>=500)) {
                    int delay = cooldownSeconds(response.headers().firstValue("Retry-After").orElse(null));
                    recordOutcome(selection, response.statusCode()==429 ? "429" : "failure", delay);
                } else if(response.statusCode()==429 || response.statusCode()>=500) permit.failure();
                if(response.statusCode()==429){
                    if (adapter(cloudPlatform).rotateOn429() && routingEnabled() && System.nanoTime() < deadline
                            && properties().enabledProviders().stream().anyMatch(source -> !attempted.contains(source.id())
                                && source.model().equals(adapter(cloudPlatform).normalizeModel(requestModel)))) continue;
                    int delay=ProviderGuard.retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(null),java.time.Instant.now());
                    if(delay>0) throw new ProviderGuard.Unavailable("AI provider trả HTTP 429",delay);
                }
                throw new Failure("HTTP_"+response.statusCode(),"AI provider trả HTTP " + response.statusCode());
            }
            JsonNode envelope = json.readTree(response.body());
            AiCallObservation.usage(envelope);
            Result result = selectedAdapter.decode(json, target, call, envelope);
            if (routingEnabled()) recordOutcome(selection, "success", 0);
            permit.success();
            return result;
          }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yêu cầu AI bị gián đoạn.", exception);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new Failure("INVALID_JSON","AI JSON không hợp lệ.");
        }
        }
    }

    public JsonNode previewLocal(Request call, String baseUrl) {
        var target = new AiTextProvider.Target(baseUrl + "/api/chat", call.model(), "", null);
        var selected = adapter("ollama"); selected.validateTarget(target);
        return selected.payload(json, target, call, textProperties.profile("ollama", call.model()));
    }

    public Result completeLocal(Request call, String baseUrl) {
        if (!java.util.Set.of("conversation_local", "coach_local").contains(call.profile()))
            throw new IllegalArgumentException("Local AI profile required");
        var target = new AiTextProvider.Target(baseUrl + "/api/chat", call.model(), "", null);
        var selected = adapter("ollama");
        var request = selected.prepare(json, target, call, textProperties.profile("ollama", call.model()), call.remaining());
        var pending = http.sendAsync(request, ignored -> BoundedProviderBody.bytes(24000));
        try {
            var response = pending.get(call.remaining().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new Failure("HTTP_" + response.statusCode(), "Local AI rejected request");
            return selected.decode(json, target, call, json.readTree(response.body()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new Failure("INTERRUPTED", "Local AI interrupted; outcome unknown");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new Failure("TIMEOUT_OR_TRANSPORT", "Local AI outcome unknown; automatic replay disabled");
        } finally { pending.cancel(true); }
    }

    private boolean routingEnabled() {
        return !local && properties() != null && properties().quotaEnabled()
                && !properties().enabledProviders().isEmpty() && quota != null;
    }

    private ProviderSelection selectProvider(String instruction, String input, String requestModel, java.util.Set<String> attempted) {
        if (!routingEnabled()) {
            if (databaseEnabled() || !cloudPlatform.equals("gemini")) throw new IllegalStateException("Cloud account unavailable; no fallback");
            return new ProviderSelection(null, requestModel, url, key);
        }
        var providers = properties().enabledProviders();
        int start = Math.floorMod(nextProvider.getAndIncrement(), providers.size());
        // Byte-based upper bound is deliberately conservative for Vietnamese input.
        int estimatedTokens = Math.addExact(Math.addExact(instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length), 2300);
        long retryMs = Long.MAX_VALUE;
        for (int offset = 0; offset < providers.size(); offset++) {
            var provider = providers.get((start + offset) % providers.size());
            if (attempted.contains(provider.id())) continue;
            String selectedModel = adapter(cloudPlatform).normalizeModel(requestModel);
            if (!provider.model().equals(selectedModel)) continue;
            var admission = quota.reserve(cloudPlatform, provider.id(), selectedModel, estimatedTokens);
            if (admission.allowed()) return new ProviderSelection(provider.id(), selectedModel, provider.url(), provider.apiKey());
            retryMs = Math.min(retryMs, admission.retryAfterMs());
        }
        int retry = retryMs == Long.MAX_VALUE ? 60 : (int)Math.min(86400, Math.max(1, (retryMs + 999) / 1000));
        throw new ProviderGuard.Unavailable("AI provider đã hết quota tạm thời.", retry, true);
    }

    private void recordOutcome(ProviderSelection selection, String outcome, int delay) {
        try { quota.outcome(cloudPlatform, selection.id(), selection.model(), outcome, delay); }
        catch (RuntimeException unavailable) {
            // Preserve the original result: a Redis outage after send is never retry-safe.
            org.slf4j.LoggerFactory.getLogger(AiTextService.class).warn("AI quota outcome persistence unavailable");
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
