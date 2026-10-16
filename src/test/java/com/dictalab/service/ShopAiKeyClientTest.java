package com.dictalab.service;

import com.dictalab.config.ConversationProperties;
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

class ShopAiKeyClientTest {
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
    private ShopAiKeyClient client;

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
        client = new ShopAiKeyClient(json, "test-only-key", "claude-sonnet-4-5",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void conversationOverrideDoesNotChangeTheAssessmentModelAndPreservesVietnamese() {
        assertThat(client.completeJson("Tạo câu nghe.", "{\"scenario\":\"Hỏi đường\"}", "gemini-2.5-flash").path("ok").asBoolean()).isTrue();
        assertThat(received.get().path("model").asText()).isEqualTo("gemini-2.5-flash");
        assertThat(received.get().path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(received.get().path("messages").path(0).path("content").asText()).isEqualTo("Tạo câu nghe.");
        assertThat(received.get().path("messages").path(1).path("content").asText()).contains("Hỏi đường");
        assertThat(received.get().path("temperature").asInt()).isZero();
        assertThat(received.get().path("max_tokens").asInt()).isEqualTo(2200);
        assertThat(authorization.get()).isEqualTo("Bearer test-only-key");
        assertThat(received.get().has("session_id")).isFalse();
        assertThat(received.get().has("conversation_id")).isFalse();
        client.completeJson("Assessment", "Transcript");
        assertThat(received.get().path("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(client.model()).isEqualTo("claude-sonnet-4-5");
    }

    @Test void fencesRemainCompatibleButEmptyOversizedOrAmbiguousOutputIsRejected() {
        content.set("```json\n{\"ok\":true}\n```");
        assertThat(client.completeJson("Instruction", "Context").path("ok").asBoolean()).isTrue();
        for (String invalid : List.of("", " ", "[]", "{\"ok\":true} {\"ok\":false}", "x".repeat(12_001))) {
            content.set(invalid);
            assertThatThrownBy(() -> client.completeJson("Instruction", "Context", "gemini-2.5-flash"))
                    .isInstanceOf(IllegalStateException.class);
        }
        status.set(429);
        assertThatThrownBy(() -> client.completeJson("Instruction", "Context"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("429");
    }

    @Test void conversationDefaultsToFlashWithoutLeakingKeys() {
        var config = new ConversationProperties(true, null, "secret-value", null, null, null);
        assertThat(config.generationModel()).isEqualTo("gemini-2.5-flash");
        assertThat(config.toString()).doesNotContain("secret-value");
    }

    @Test void missingKeyRejectsRequestsBeforeSendingAnyContext() {
        var unconfigured = new ShopAiKeyClient(json, "", "claude-sonnet-4-5",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        assertThat(unconfigured.configured()).isFalse();
        assertThatThrownBy(() -> unconfigured.completeJson("Instruction", "Learner evidence"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chưa được cấu hình");
        assertThat(received.get()).isNull();
    }

    @Test void truncatedAndMalformedOutputAreTypedFailuresWithoutRetry() {
        finishReason.set("length");
        assertThatThrownBy(()->client.completeJson("synthetic","synthetic"))
                .isInstanceOfSatisfying(ShopAiKeyClient.Failure.class,f->assertThat(f.reason()).isEqualTo("OUTPUT_TRUNCATED"));
        assertThat(requests.get()).isEqualTo(1);
        finishReason.set("stop"); content.set("{broken}");
        assertThatThrownBy(()->client.completeJson("synthetic","synthetic"))
                .isInstanceOfSatisfying(ShopAiKeyClient.Failure.class,f->assertThat(f.reason()).isEqualTo("INVALID_JSON"));
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
            var single=new ShopAiKeyClient(json,"fake","fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions",
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
            var single=new ShopAiKeyClient(json,"fake","fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions",
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
                    .isInstanceOfSatisfying(ShopAiKeyClient.Failure.class,f->assertThat(f.reason()).isEqualTo("TIMEOUT"));
            assertThat(requests.get()).isEqualTo(1);
        } finally {stalled.countDown();}
        assertThat(client.completeJson("synthetic","synthetic").path("ok").asBoolean()).isTrue();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void remainingConversationBudgetBoundsCallsAndRejectsExpiredWorkWithoutSending() {
        assertThatThrownBy(() -> client.completeJson("synthetic", "synthetic", "fixture", java.time.Duration.ZERO))
                .isInstanceOfSatisfying(ShopAiKeyClient.Failure.class, failure -> assertThat(failure.reason()).isEqualTo("TIMEOUT"));
        assertThat(requests.get()).isZero();
        var stalled = new java.util.concurrent.CountDownLatch(1);
        gate.set(stalled);
        long began = System.nanoTime();
        try {
            assertThatThrownBy(() -> client.completeJson("synthetic", "synthetic", "fixture", java.time.Duration.ofMillis(200)))
                    .isInstanceOfSatisfying(ShopAiKeyClient.Failure.class, failure -> assertThat(failure.reason()).isEqualTo("TIMEOUT"));
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)).isLessThan(3000L);
            assertThat(requests.get()).isEqualTo(1);
        } finally { stalled.countDown(); }
        assertThat(client.completeJson("synthetic", "synthetic", "fixture", java.time.Duration.ofSeconds(2)).path("ok").asBoolean()).isTrue();
    }
}
