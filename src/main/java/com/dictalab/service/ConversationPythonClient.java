package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP boundary for the optional Python generation service. Local Ollama remains the default. */
@Service
public final class ConversationPythonClient implements ConversationAiClient {

    private static final Logger log = LoggerFactory.getLogger(ConversationPythonClient.class);

    /** Allowlisted context keys sent to the Python AI Platform. Keys outside this set are dropped
     *  before transmission so credentials and account data never reach the model prompt. */
    private static final Set<String> CONTEXT_ALLOWLIST = Set.of(
            "scenario", "difficulty", "turnNumber", "partnerRole", "learnerRole",
            "levelProfile", "listeningSkills", "scenarioJourney", "history",
            "recentPractice", "practiceFocus", "sessionBlueprint", "variationSeed",
            "previouslyTestedDetails", "weakSkills"
    );
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final ConversationGenerationProperties config;
    private final ProviderGuard guard;
    private final MeterRegistry metrics;

    public ConversationPythonClient(ObjectMapper json, ConversationGenerationProperties config,
                                    MeterRegistry metrics, io.micrometer.observation.ObservationRegistry observations) {
        this.json = json;
        this.config = config;
        this.metrics = metrics;
        this.guard = new ProviderGuard("conversation-python", 1, metrics, observations, 5, Duration.ofSeconds(30));
    }

    @Override public boolean enabled() { return config.python(); }

    @Override public int flowTimeoutSeconds() { return config.pythonFlowTimeoutSeconds(); }

    @Override public ConversationLocalClient.GeneratedTask completeTask(String level, String input, Duration remaining) {
        Duration timeout = Duration.ofMillis(Math.min(config.pythonTimeoutSeconds() * 1000L, remaining.toMillis()));
        if (timeout.toMillis() < 1) throw new IllegalStateException("Conversation Python deadline exhausted");
        String requestId = UUID.randomUUID().toString();
        byte[] bytes = requestBody(level, input, requestId).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 64_000) throw new IllegalArgumentException("Conversation exceeds Python request limit");
        long startedNs = System.nanoTime();
        String outcome = "error";
        try (var permit = guard.enter()) {
            var builder = HttpRequest.newBuilder(URI.create(config.pythonBaseUrl() + "/v1/conversation/tasks"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .header("X-Request-Id", requestId).header("Idempotency-Key", requestId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
            if (!config.pythonServiceToken().isBlank()) builder.header("X-Service-Token", config.pythonServiceToken());
            var pending = http.sendAsync(builder.build(), ignored -> BoundedProviderBody.bytes(32_000));
            try {
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                int status = response.statusCode();
                if (status == 401) throw new IllegalStateException("Python conversation configuration or security failure");
                if (status == 429) throw new ProviderGuard.Unavailable("Python AI đang bận. Vui lòng thử lại sau.", 5);
                if (status == 400) throw new IllegalArgumentException("Python conversation request rejected");
                if (status == 409) throw new IllegalStateException("Python conversation idempotency conflict");
                if (status == 503 || status == 504)
                    throw new IllegalStateException("Python conversation service unavailable");
                if (status != 200) throw new IllegalStateException("Python conversation HTTP " + status);
                JsonNode envelope = json.readTree(response.body());
                JsonNode task = envelope.path("task");
                if (!task.isObject()) throw new IllegalStateException("Python conversation task missing");
                permit.success();
                outcome = "success";
                return new ConversationLocalClient.GeneratedTask(task);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                permit.failure();
                outcome = "interrupted";
                throw new IllegalStateException("Python conversation interrupted");
            } catch (ExecutionException | TimeoutException failed) {
                permit.failure();
                outcome = "timeout";
                throw new IllegalStateException("Python conversation transport or deadline failure");
            } catch (RuntimeException failed) {
                permit.failure();
                throw failed;
            } finally {
                pending.cancel(true);
                long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
                log.info("conversation_python_request request_id={} outcome={} duration_ms={} provider=python",
                        requestId, outcome, durationMs);
                metrics.timer("dictalab.conversation.python.duration",
                        "outcome", outcome, "provider", "python"
                ).record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
    }

    String requestBody(String level, String input) {
        return requestBody(level, input, UUID.randomUUID().toString());
    }

    private String requestBody(String level, String input, String requestId) {
        try {
            JsonNode context = json.readTree(input);
            var body = new LinkedHashMap<String, Object>();
            body.put("requestId", requestId);
            body.put("idempotencyKey", requestId);
            body.put("level", level);
            body.put("turnNumber", context.path("turnNumber").asInt());
            body.put("promptVersion", ConversationGenerationPrompt.VERSION);
            body.put("instruction", ConversationGenerationPrompt.instruction(level, context.path("turnNumber").asInt())
                    + ConversationGenerationPrompt.turnBrief(context));
            body.put("context", filterContext(context));
            return json.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid conversation generation input", e);
        }
    }

    /** Returns a copy of the context JsonNode containing only allowlisted keys.
     *  This ensures credentials and account data never reach the Python AI Platform. */
    private Map<String, Object> filterContext(JsonNode context) {
        var filtered = new LinkedHashMap<String, Object>();
        context.properties().forEach(entry -> {
            if (CONTEXT_ALLOWLIST.contains(entry.getKey())) {
                filtered.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class));
            }
        });
        return filtered;
    }
}
