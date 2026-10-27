package com.dictalab.service;

import com.dictalab.config.AiPlatformProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Internal job transport. Only existing background workers wait for results; Java owns all domain writes. */
@Service
public class AiPlatformClient {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final AiPlatformProperties config;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final java.util.concurrent.ThreadPoolExecutor shadows = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), task -> {
                var thread = new Thread(task, "ai-capability-shadow"); thread.setDaemon(true); return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    public AiPlatformClient(ObjectMapper json, AiPlatformProperties config,
                            io.micrometer.core.instrument.MeterRegistry metrics) {
        this.json = json; this.config = config; this.metrics = metrics;
    }

    public boolean enabled(String kind, long user) {
        return config.mode(kind).equals("python") || config.mode(kind).equals("canary") && config.canaryUsers().contains(user);
    }
    public boolean shadow(String kind) { return config.mode(kind).equals("shadow"); }
    public boolean local() { return config.textProvider().equals("ollama"); }

    public JsonNode submitDemo(long user, String id, Instant deadline, JsonNode payload) {
        String scope = AiBackgroundJobs.hash("ai-scope/" + config.serviceToken() + "/" + user);
        String body = json.writeValueAsString(Map.of("schemaVersion", "1", "jobId", id, "requestId", id,
                "userScope", scope, "idempotencyKey", "model-demo/" + id, "deadline", deadline.toString(),
                "payload", redact(payload)));
        if (body.getBytes(StandardCharsets.UTF_8).length > 64000) throw new IllegalArgumentException("AI job input exceeds limit");
        return exchange("/v1/conversation/jobs", body, scope, id, "model-demo/" + id,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    public JsonNode demoStatus(long user, String id) {
        String scope = AiBackgroundJobs.hash("ai-scope/" + config.serviceToken() + "/" + user);
        return exchange("/v1/jobs/" + UUID.fromString(id), null, scope, id, "model-demo/" + id,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    public void compare(String kind, long user, String key, Object payload, Runnable reserve) {
        if (!shadow(kind) || !config.canaryUsers().contains(user)) return;
        try {
            shadows.execute(() -> {
                try {
                    reserve.run(); complete(kind, user, "shadow/" + key, payload);
                    metrics.counter("dictalab.ai.platform.shadow", "kind", kind, "outcome", "valid").increment();
                } catch (RuntimeException unavailable) {
                    metrics.counter("dictalab.ai.platform.shadow", "kind", kind, "outcome", "failed").increment();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            metrics.counter("dictalab.ai.platform.shadow", "kind", kind, "outcome", "skipped").increment();
        }
    }

    @jakarta.annotation.PreDestroy void stopShadows() { shadows.shutdownNow(); }

    public JsonNode complete(String kind, long user, String key, Object payload) {
        String endpoint = switch (kind) { case "tts" -> "/v1/tts/jobs"; case "coach" -> "/v1/coach/reports";
            case "review" -> "/v1/review/tasks"; default -> throw new IllegalArgumentException("Unsupported AI capability"); };
        String scope = AiBackgroundJobs.hash("ai-scope/" + config.serviceToken() + "/" + user);
        String requestId = UUID.randomUUID().toString();
        String idem = AiBackgroundJobs.hash(kind + "/" + key);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
        String body = json.writeValueAsString(Map.of("schemaVersion", "1", "jobId", requestId, "requestId", requestId,
                "userScope", scope, "idempotencyKey", idem, "deadline", Instant.now().plusSeconds(config.timeoutSeconds()).toString(),
                "payload", redact(json.valueToTree(payload))));
        if (body.getBytes(StandardCharsets.UTF_8).length > 64000) throw new IllegalArgumentException("AI job input exceeds limit");
        var started = io.micrometer.core.instrument.Timer.start(metrics);
        String outcome = "failed";
        try {
            JsonNode state = exchange(endpoint, body, scope, requestId, idem, deadline);
            String jobId = state.path("jobId").asText();
            if (!UUID.fromString(jobId).toString().equals(jobId)) throw new IllegalStateException("Invalid job ID");
            while (Set.of("PENDING", "RUNNING").contains(state.path("status").asText())) {
                if (deadline - System.nanoTime() <= 0) throw new IllegalStateException("AI job deadline exhausted");
                Thread.sleep(250);
                state = exchange("/v1/jobs/" + jobId, null, scope, requestId, idem, deadline);
                if (!jobId.equals(state.path("jobId").asText())) throw new IllegalStateException("AI job ID mismatch");
            }
            if (!"1".equals(state.path("schemaVersion").asText()) || !"DONE".equals(state.path("status").asText()))
                throw new IllegalStateException("AI job did not complete");
            JsonNode result = state.path("result");
            if (!"1".equals(result.path("schemaVersion").asText()) || !result.path("task").isObject()
                    || !Set.of("ollama", "gemini", "deepseek", "openai_compatible", "cartesia").contains(result.path("provider").asText())
                    || !result.path("promptHash").asText().matches("[a-f0-9]{64}"))
                throw new IllegalStateException("Invalid AI job result");
            if (!kind.equals("tts") && !config.textProvider().equals(result.path("provider").asText()))
                throw new IllegalStateException("AI provider differs from Java quota configuration");
            if (kind.equals("tts") && !"cartesia".equals(result.path("provider").asText()))
                throw new IllegalStateException("Invalid TTS provider");
            for (String field : List.of("model", "promptVersion", "generationId"))
                if (!result.path(field).isTextual() || result.path(field).asText().isBlank() || result.path(field).asText().length() > 128)
                    throw new IllegalStateException("Missing AI metadata");
            outcome = "success";
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI job interrupted");
        } finally {
            started.stop(metrics.timer("dictalab.ai.platform.duration", "kind", kind, "outcome", outcome));
        }
    }

    private JsonNode exchange(String path, String body, String scope, String requestId, String idem, long deadline) {
        long millis = Math.min(10000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (millis < 1) throw new IllegalStateException("AI job deadline exhausted");
        var builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + path)).timeout(Duration.ofMillis(millis))
                .header("X-Service-Token", config.serviceToken()).header("X-User-Scope", scope)
                .header("X-AI-Provider", config.textProvider())
                .header("X-Request-Id", requestId).header("Idempotency-Key", idem);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var pending = http.sendAsync(builder.build(), ignored -> BoundedProviderBody.bytes(4_100_000));
        try {
            var response = pending.get(millis, TimeUnit.MILLISECONDS);
            if (response.statusCode() != (body == null ? 200 : 202)) throw new IllegalStateException("AI job transport rejected request");
            return json.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI job interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            throw new IllegalStateException("AI job transport or deadline failure");
        } finally { pending.cancel(true); }
    }

    public byte[] audio(JsonNode result) {
        JsonNode task = result.path("task");
        if (!"audio/wav".equals(task.path("contentType").asText())) throw new IllegalStateException("Invalid audio content type");
        byte[] bytes = Base64.getDecoder().decode(task.path("audioBase64").asText());
        if (bytes.length != task.path("size").asInt() || bytes.length < 44 || bytes.length > 3_000_000
                || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'A' || bytes[10] != 'V' || bytes[11] != 'E')
            throw new IllegalStateException("Invalid audio bytes");
        if (java.nio.ByteBuffer.wrap(bytes, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() != bytes.length - 8)
            throw new IllegalStateException("Invalid WAV length");
        try (var audio = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var format = audio.getFormat();
            if (format.getChannels() != 1 || format.getSampleRate() != 24000 || format.getSampleSizeInBits() != 16
                    || format.isBigEndian() || !format.getEncoding().equals(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED)
                    || audio.getFrameLength() <= 0 || audio.readAllBytes().length != audio.getFrameLength() * 2)
                throw new IllegalStateException("Invalid PCM audio");
        } catch (java.io.IOException | javax.sound.sampled.UnsupportedAudioFileException invalid) {
            throw new IllegalStateException("Invalid WAV audio");
        }
        return bytes;
    }

    private JsonNode redact(JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            value.properties().forEach(entry -> result.set(entry.getKey(), Set.of("id", "pattern", "kind", "level")
                    .contains(entry.getKey()) ? entry.getValue() : redact(entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode(); value.forEach(item -> result.add(redact(item))); return result;
        }
        if (value.isTextual()) return json.valueToTree(value.asText()
                .replaceAll("(?i)Bearer\\s+\\S+", "[REDACTED]")
                .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[REDACTED]")
                .replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "[REDACTED]")
                .replaceAll("[A-Za-z0-9_+/\\-]{32,}={0,2}", "[REDACTED]"));
        return value;
    }
}
