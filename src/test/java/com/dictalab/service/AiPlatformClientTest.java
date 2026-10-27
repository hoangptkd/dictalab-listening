package com.dictalab.service;

import com.dictalab.config.AiPlatformProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AiPlatformClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private AiPlatformClient client;
    private String provider = "ollama";
    private int status = 202;
    private final AtomicInteger calls = new AtomicInteger();
    private tools.jackson.databind.JsonNode input;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Service-Token")).isEqualTo("test-only");
            assertThat(exchange.getRequestHeaders().getFirst("X-User-Scope")).matches("[a-f0-9]{64}");
            assertThat(exchange.getRequestHeaders().getFirst("X-AI-Provider")).isEqualTo("ollama");
            input = json.readTree(exchange.getRequestBody().readAllBytes());
            var result = Map.of("schemaVersion", "1", "provider", provider, "model", "qwen3.5:9b", "promptVersion", "RECALL_PLATFORM_V1",
                    "promptHash", "a".repeat(64), "generationId", UUID.randomUUID().toString(), "task", Map.of("facts", List.of()));
            byte[] body = json.writeValueAsBytes(Map.of("schemaVersion", "1", "jobId", input.path("jobId").asText(), "status", "DONE", "result", result));
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        client = new AiPlatformClient(json, config("python", Set.of()), new SimpleMeterRegistry());
    }
    @AfterEach void cleanup() { client.stopShadows(); server.stop(0); }

    @Test void genericTextAuthenticatesAndVerifiesProvenanceWithNoAutomaticRetry() {
        var actualProvider = new java.util.concurrent.atomic.AtomicReference<>("ollama");
        server.createContext("/v1/text/completions", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Service-Token")).isEqualTo("t".repeat(40));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            input = json.readTree(exchange.getRequestBody().readAllBytes());
            byte[] body = json.writeValueAsBytes(Map.of("schemaVersion", "1", "requestId", input.path("requestId").asText(),
                    "provider", actualProvider.get(), "model", "qwen3.5:9b", "task", Map.of("ok", true)));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        var remote = new AiPlatformClient(json, new AiPlatformProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "t".repeat(40), "ollama", 5, "legacy", "legacy", "legacy", Set.of()), new SimpleMeterRegistry());
        assertThat(remote.completeText("Return JSON.", "Evidence", "qwen3.5:9b", java.time.Duration.ofSeconds(2))
                .path("ok").asBoolean()).isTrue();
        assertThat(input.path("provider").asText()).isEqualTo("ollama");
        assertThat(input.path("instruction").asText()).isEqualTo("Return JSON.");
        assertThat(java.time.Instant.parse(input.path("deadline").asText())).isAfter(java.time.Instant.now());
        assertThat(input.toString()).doesNotContain("apiKey", "endpoint");
        actualProvider.set("gemini");
        assertThatThrownBy(() -> remote.completeText("Return JSON.", "Evidence", "qwen3.5:9b", java.time.Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("provenance");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void genericTextRejectsWeakTokenBeforeNetwork() {
        assertThatThrownBy(() -> client.completeText("JSON", "Evidence", "model", java.time.Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("strong service token");
        assertThat(calls.get()).isZero();
    }
    private AiPlatformProperties config(String mode, Set<Long> users) {
        return new AiPlatformProperties("http://127.0.0.1:" + server.getAddress().getPort(), "test-only", "ollama", 5,
                mode, mode, mode, users);
    }

    @Test void transportRedactsFreeTextAndCarriesVersionedScopeAndDeadline() {
        assertThat(client.complete("review", 42, "stable-attempt", Map.of("kind", "review", "recall", "user@example.com Bearer private",
                "facts", List.of(Map.of("id", "F1", "text", "nine")))).path("model").asText()).isEqualTo("qwen3.5:9b");
        assertThat(input.toString()).doesNotContain("user@example.com", "Bearer private");
        assertThat(input.path("payload").path("facts").path(0).path("id").asText()).isEqualTo("F1");
        assertThat(input.path("schemaVersion").asText()).isEqualTo("1");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void mismatchAndTransportFailureNeverTriggerLegacyOrAutomaticRetry() {
        provider = "gemini";
        assertThatThrownBy(() -> client.complete("review", 42, "attempt", Map.of())).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(1);
        status = 503;
        assertThatThrownBy(() -> client.complete("review", 42, "other", Map.of())).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void independentCanaryAndRollbackFlagsAndSecrets() {
        var canary = new AiPlatformClient(json, config("canary", Set.of(42L)), new SimpleMeterRegistry());
        assertThat(canary.enabled("coach", 42)).isTrue();
        assertThat(canary.enabled("coach", 43)).isFalse();
        var legacy = new AiPlatformClient(json, config("legacy", Set.of()), new SimpleMeterRegistry());
        assertThat(legacy.enabled("tts", 42)).isFalse();
        assertThat(config("legacy", Set.of()).toString()).doesNotContain("test-only");
        assertThatThrownBy(() -> config("canary", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiPlatformProperties("http://10.0.0.2:8091", "token", "ollama", 5,
                "legacy", "legacy", "legacy", Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void audioChecksTypeSizeAndCompletePcmWav() {
        byte[] wav = java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes()).putInt(38).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1)
                .putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16)
                .put("data".getBytes()).putInt(2).putShort((short)0).array();
        var result = json.valueToTree(Map.of("task", Map.of("contentType", "audio/wav", "size", wav.length,
                "audioBase64", Base64.getEncoder().encodeToString(wav))));
        assertThat(client.audio(result)).isEqualTo(wav);
        wav[4] = 0;
        var invalid = json.valueToTree(Map.of("task", Map.of("contentType", "audio/wav", "size", wav.length,
                "audioBase64", Base64.getEncoder().encodeToString(wav))));
        assertThatThrownBy(() -> client.audio(invalid)).isInstanceOf(IllegalStateException.class);
    }

    @Test void shadowIsAllowlistedNonblockingAndSkipsBusyWork() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var shadow = new AiPlatformClient(json, config("shadow", Set.of(42L)), metrics);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            shadow.compare("review", 43, "other-user", Map.of(), () -> fail("outside the allowlist"));
            shadow.compare("review", 42, "first", Map.of(), () -> {
                entered.countDown();
                try { assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            shadow.compare("review", 42, "busy", Map.of(), () -> fail("no queued shadow work"));
            assertThat(metrics.counter("dictalab.ai.platform.shadow", "kind", "review", "outcome", "skipped").count()).isEqualTo(1);
            release.countDown();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> calls.get() == 1);
        } finally { release.countDown(); shadow.stopShadows(); }
    }
}
