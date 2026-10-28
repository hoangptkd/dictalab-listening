package com.dictalab.service;

import com.dictalab.config.ConversationProperties;
import com.dictalab.config.GeminiProviderProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class GeminiClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> content = new AtomicReference<>("{\"ok\":true}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private final AtomicReference<String> finishReason = new AtomicReference<>("stop");
    private final AtomicReference<java.util.concurrent.CountDownLatch> gate = new AtomicReference<>(new java.util.concurrent.CountDownLatch(0));
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    private HttpServer server;
    private GeminiClient client;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason",finishReason.get(),"message", Map.of("content", content.get())))))
                    .getBytes(StandardCharsets.UTF_8);
            try { gate.get().await(35, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if(retryAfter.get()!=null)exchange.getResponseHeaders().set("Retry-After",retryAfter.get());
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        client = new GeminiClient(json, "test-only-key", "gemini-3.5-flash-lite",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void remoteTextSkipsCoreRegistryAndQuotaAndNeverFallsBack() {
        var registry = org.mockito.Mockito.mock(AiProviderRegistry.class);
        var platform = org.mockito.Mockito.mock(AiPlatformClient.class);
        org.mockito.Mockito.when(platform.textConfigured()).thenReturn(true);
        org.mockito.Mockito.when(platform.completeText("Review", "Evidence", "ai-owned-model", java.time.Duration.ofSeconds(60)))
                .thenReturn(json.valueToTree(Map.of("ok", true)));
        client.configureRegistry(registry);
        client.configureTextTransport(platform, "python", "ai-owned-model");
        assertThat(client.configured()).isTrue();
        assertThat(client.model()).isEqualTo("ai-owned-model");
        assertThat(client.completeJson("Review", "Evidence").path("ok").asBoolean()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(registry);
        assertThat(requests.get()).isZero();
        org.mockito.Mockito.when(platform.completeText("Review", "Evidence", "ai-owned-model", java.time.Duration.ofSeconds(60)))
                .thenThrow(new IllegalStateException("unknown outcome secret-value"));
        assertThatThrownBy(() -> client.completeJson("Review", "Evidence"))
                .isInstanceOf(GeminiClient.Failure.class).hasMessageNotContaining("secret-value");
        assertThat(requests.get()).isZero();
        org.mockito.Mockito.verifyNoInteractions(registry);
    }

    @Test void invalidRemoteTransportFailsConfiguration() {
        var platform = org.mockito.Mockito.mock(AiPlatformClient.class);
        assertThatThrownBy(() -> client.configureTextTransport(platform, "python", "model"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.configureTextTransport(platform, "unknown", "model"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void conversationOverrideDoesNotChangeTheAssessmentModelAndPreservesVietnamese() {
        assertThat(client.completeJson("Tạo câu nghe.", "{\"scenario\":\"Hỏi đường\"}", "gemini-3.5-flash-lite").path("ok").asBoolean()).isTrue();
        assertThat(received.get().path("model").asText()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(received.get().path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(received.get().path("messages").path(0).path("content").asText()).isEqualTo("Tạo câu nghe.");
        assertThat(received.get().path("messages").path(1).path("content").asText()).contains("Hỏi đường");
        assertThat(received.get().path("temperature").asInt()).isZero();
        assertThat(received.get().path("max_tokens").asInt()).isEqualTo(2200);
        assertThat(authorization.get()).isEqualTo("Bearer test-only-key");
        assertThat(received.get().has("session_id")).isFalse();
        assertThat(received.get().has("conversation_id")).isFalse();
        client.completeJson("Assessment", "Transcript");
        assertThat(received.get().path("model").asText()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(client.model()).isEqualTo("gemini-3.5-flash-lite");
    }

    @Test void fencesRemainCompatibleButEmptyOversizedOrAmbiguousOutputIsRejected() {
        content.set("```json\n{\"ok\":true}\n```");
        assertThat(client.completeJson("Instruction", "Context").path("ok").asBoolean()).isTrue();
        for (String invalid : List.of("", " ", "[]", "{\"ok\":true} {\"ok\":false}", "x".repeat(12_001))) {
            content.set(invalid);
            assertThatThrownBy(() -> client.completeJson("Instruction", "Context", "gemini-3.5-flash-lite"))
                    .isInstanceOf(IllegalStateException.class);
        }
        status.set(429);
        assertThatThrownBy(() -> client.completeJson("Instruction", "Context"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("429");
    }

    @Test void conversationDefaultsToFlashWithoutLeakingKeys() {
        var config = new ConversationProperties(true, null, "secret-value", null, null, null);
        assertThat(config.generationModel()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(config.toString()).doesNotContain("secret-value");
    }

    @Test void localModeNeverSendsACloudCredentialAndRejectsIncompleteOutput() throws Exception {
        server.createContext("/api/chat", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = json.writeValueAsBytes(Map.of("done", true, "done_reason", finishReason.get(),
                    "message", Map.of("content", content.get())));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        client.configureLocal("ollama", "http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5:9b");
        assertThat(client.completeJson("Review", "Evidence", "cloud-model").path("ok").asBoolean()).isTrue();
        assertThat(received.get().path("model").asText()).isEqualTo("qwen3.5:9b");
        assertThat(authorization.get()).isNull();
        finishReason.set("length");
        assertThatThrownBy(() -> client.completeJson("Review", "Evidence")).isInstanceOf(GeminiClient.Failure.class);
    }

    @Test void missingKeyRejectsRequestsBeforeSendingAnyContext() {
        var unconfigured = new GeminiClient(json, "", "gemini-3.5-flash-lite",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        assertThat(unconfigured.configured()).isFalse();
        assertThatThrownBy(() -> unconfigured.completeJson("Instruction", "Learner evidence"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chưa được cấu hình");
        assertThat(received.get()).isNull();
    }

    @Test void databaseDeepseekUsesOnlyItsOwnAccountAndNeverFallsBack() {
        var provider = new GeminiProviderProperties.Provider("ds1","ds-account","fake-deepseek","deepseek-flash",
            "http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions",1,0,100,1,true,0);
        var props = new GeminiProviderProperties(List.of(provider),1,100,true,"memory");
        var registry = org.mockito.Mockito.mock(AiProviderRegistry.class);
        org.mockito.BDDMockito.given(registry.databaseEnabled()).willReturn(true);
        org.mockito.BDDMockito.given(registry.properties("deepseek")).willReturn(props);
        org.mockito.BDDMockito.given(registry.model("deepseek")).willReturn("deepseek-flash");
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var quota = new GeminiQuotaCoordinator(props,metrics,null,java.time.Clock.systemUTC());
        quota.configureRegistry(registry);
        var routed = new GeminiClient(json,"must-not-fallback","gemini-3.5-flash-lite","http://127.0.0.1:1/wrong",8,
            metrics,io.micrometer.observation.ObservationRegistry.NOOP,5,30,props,quota);
        routed.configureRegistry(registry);
        routed.configureLocal("deepseek","http://127.0.0.1:11434","qwen3.5:9b");
        assertThat(routed.completeJson("Instruction","Context").path("ok").asBoolean()).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer fake-deepseek");
        assertThat(received.get().path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(received.get().path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(received.get().path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThatThrownBy(()->routed.completeJson("Instruction","Context")).isInstanceOf(ProviderGuard.Unavailable.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void truncatedAndMalformedOutputAreTypedFailuresWithoutRetry() {
        finishReason.set("length");
        assertThatThrownBy(()->client.completeJson("synthetic","synthetic"))
                .isInstanceOfSatisfying(GeminiClient.Failure.class,f->assertThat(f.reason()).isEqualTo("OUTPUT_TRUNCATED"));
        assertThat(requests.get()).isEqualTo(1);
        finishReason.set("stop"); content.set("{broken}");
        assertThatThrownBy(()->client.completeJson("synthetic","synthetic"))
                .isInstanceOfSatisfying(GeminiClient.Failure.class,f->assertThat(f.reason()).isEqualTo("INVALID_JSON"));
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test void rejectsOversizedDownloadBeforeParsingIt() {
        content.set("x".repeat(150_000));
        assertThatThrownBy(()->client.completeJson("Instruction","Context"))
                .isInstanceOf(IllegalStateException.class);
        content.set("{\"ok\":true}");
        assertThat(client.completeJson("Instruction","Context").path("ok").asBoolean()).isTrue();
    }

    @Test void provider429And500ReleasePermitWithoutHiddenRetries() {
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try{
            var single=new GeminiClient(json,"fake","fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions",
                    1,metrics,io.micrometer.observation.ObservationRegistry.NOOP,5,30);
            status.set(429);retryAfter.set("2");
            assertThatThrownBy(()->single.completeJson("fixture","fixture"))
                    .isInstanceOfSatisfying(ProviderGuard.Unavailable.class,error->assertThat(error.retryAfter()).isEqualTo(2));
            assertThat(requests.get()).isEqualTo(1);
            assertThat(metrics.get("dictalab.provider.inflight").gauge().value()).isZero();
            status.set(500);
            assertThatThrownBy(()->single.completeJson("fixture","fixture")).hasMessageContaining("500");
            assertThat(requests.get()).isEqualTo(2);
            status.set(200);
            assertThat(single.completeJson("fixture","fixture").path("ok").asBoolean()).isTrue();
            assertThat(requests.get()).isEqualTo(3);
        }finally{metrics.close();}
    }

    @Test @org.junit.jupiter.api.Timeout(45)
    void actualHttpTimeoutReleasesTheOnlyPermitAndCanRecover() {
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var stalled=new java.util.concurrent.CountDownLatch(1);gate.set(stalled);
        try{
            var single=new GeminiClient(json,"fake","fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions",
                    1,metrics,io.micrometer.observation.ObservationRegistry.NOOP,5,30);
            long began=System.nanoTime();
            assertThatThrownBy(()->single.completeJson("fixture","fixture","fixture")).isInstanceOf(IllegalStateException.class);
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-began)).isBetween(25L,40L);
            assertThat(requests.get()).isEqualTo(1);
            assertThat(metrics.get("dictalab.provider.inflight").gauge().value()).isZero();
            stalled.countDown();
            assertThat(single.completeJson("fixture","fixture","fixture").path("ok").asBoolean()).isTrue();
            assertThat(requests.get()).isEqualTo(2);
        }finally{stalled.countDown();metrics.close();}
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void configuredDeadlineReturnsTypedTimeoutWithoutRetryAndRecovers() {
        client.configureTimeouts(1,1);
        var stalled=new java.util.concurrent.CountDownLatch(1);gate.set(stalled);
        try {
            assertThatThrownBy(()->client.completeJson("synthetic","synthetic"))
                    .isInstanceOfSatisfying(GeminiClient.Failure.class,f->assertThat(f.reason()).isEqualTo("TIMEOUT"));
            assertThat(requests.get()).isEqualTo(1);
        } finally {stalled.countDown();}
        assertThat(client.completeJson("synthetic","synthetic").path("ok").asBoolean()).isTrue();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void remainingConversationBudgetBoundsCallsAndRejectsExpiredWorkWithoutSending() {
        assertThatThrownBy(() -> client.completeJson("synthetic", "synthetic", "fixture", java.time.Duration.ZERO))
                .isInstanceOfSatisfying(GeminiClient.Failure.class, failure -> assertThat(failure.reason()).isEqualTo("TIMEOUT"));
        assertThat(requests.get()).isZero();
        var stalled = new java.util.concurrent.CountDownLatch(1);
        gate.set(stalled);
        long began = System.nanoTime();
        try {
            assertThatThrownBy(() -> client.completeJson("synthetic", "synthetic", "fixture", java.time.Duration.ofMillis(200)))
                    .isInstanceOfSatisfying(GeminiClient.Failure.class, failure -> assertThat(failure.reason()).isEqualTo("TIMEOUT"));
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)).isLessThan(3000L);
            assertThat(requests.get()).isEqualTo(1);
        } finally { stalled.countDown(); }
        assertThat(client.completeJson("synthetic", "synthetic", "fixture", java.time.Duration.ofSeconds(2)).path("ok").asBoolean()).isTrue();
    }

    @Test
    void enabledProviderPoolRotatesBeforeSendingAndUsesProviderModelAndKey() {
        var properties = new GeminiProviderProperties(List.of(
                new GeminiProviderProperties.Provider("a", "project-a", "key-a", "legacy-model",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", 2, 0, 0, 2, true, 0),
                new GeminiProviderProperties.Provider("b", "project-b", "key-b", "legacy-model",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", 2, 0, 0, 2, true, 1)),
                4, 0, true, "memory");
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        {
            var quota = new GeminiQuotaCoordinator(properties, metrics, null, java.time.Clock.systemUTC());
            var pooled = new GeminiClient(json, "legacy-key", "legacy-model",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", 8, metrics,
                    io.micrometer.observation.ObservationRegistry.NOOP, 5, 30, properties, quota);

            pooled.completeJson("Instruction", "Context");
            assertThat(received.get().path("model").asText()).isEqualTo("legacy-model");
            assertThat(authorization.get()).isEqualTo("Bearer key-a");
            pooled.completeJson("Instruction", "Context");
            assertThat(received.get().path("model").asText()).isEqualTo("legacy-model");
            assertThat(authorization.get()).isEqualTo("Bearer key-b");
        }
    }

    @Test
    void exhaustedProviderPoolRejectsBeforeSending() {
        var properties = new GeminiProviderProperties(List.of(
                new GeminiProviderProperties.Provider("a", "project-a", "key-a", "legacy-model",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", 1, 0, 0, 1, true, 0)),
                1, 0, true, "memory");
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var quota = new GeminiQuotaCoordinator(properties, metrics, null, java.time.Clock.systemUTC());
        var pooled = new GeminiClient(json, "legacy-key", "legacy-model",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", 8, metrics,
                io.micrometer.observation.ObservationRegistry.NOOP, 5, 30, properties, quota);

        pooled.completeJson("Instruction", "Context");
        assertThatThrownBy(() -> pooled.completeJson("Instruction", "Context"))
                .isInstanceOfSatisfying(ProviderGuard.Unavailable.class,
                        error -> assertThat(error.retrySafe()).isTrue());
        assertThat(requests.get()).isEqualTo(1);
    }
}
