package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationLocalClientTest {
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<String> content = new AtomicReference<>(ConversationGenerationPrompt.example("A1"));
    final AtomicReference<String> reason = new AtomicReference<>("stop");
    final AtomicReference<JsonNode> received = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(200), requests = new AtomicInteger();
    final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(0));
    final CountDownLatch entered = new CountDownLatch(1);
    HttpServer server;
    ExecutorService pool;
    ConversationLocalClient client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/api/chat", exchange -> {
            try {
                requests.incrementAndGet();
                received.set(json.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] bytes = json.writeValueAsString(Map.of("done", true, "done_reason", reason.get(),
                        "message", Map.of("content", content.get()))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                entered.countDown(); gate.get().await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        client = new ConversationLocalClient(json, new ConversationGenerationProperties("ollama",
                "http://127.0.0.1:" + server.getAddress().getPort(), null, 1),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    @AfterEach void stop() { gate.get().countDown(); server.stop(0); pool.shutdownNow(); }
    JsonNode call() { return client.completeJson("A1", "{\"difficulty\":\"A1\"}", Duration.ofSeconds(450)); }

    @Test void sendsLocalStructuredNonThinkingRequestWithSeparateData() {
        assertEquals(json.readTree(content.get()), call());
        assertEquals("qwen3.5:9b", received.get().path("model").asText());
        assertFalse(received.get().path("think").asBoolean(true));
        assertFalse(received.get().path("stream").asBoolean(true));
        assertEquals("object", received.get().path("format").path("type").asText());
        var order=List.of("slots","utteranceEn","facts","questionVi","translationVi","taskVi","skill");
        assertEquals(order,new ArrayList<>(received.get().path("format").path("properties").propertyNames()));
        assertEquals(json.valueToTree(order),received.get().path("format").path("required"));
        assertEquals("system", received.get().path("messages").path(0).path("role").asText());
        assertEquals("user", received.get().path("messages").path(1).path("role").asText());
        assertEquals("assistant", received.get().path("messages").path(2).path("role").asText());
        assertEquals(ConversationGenerationPrompt.wireExample("A1", 2), received.get().path("messages").path(2).path("content").asText());
        assertTrue(received.get().path("messages").path(3).path("content").asText().contains("\"difficulty\":\"A1\""));
        assertTrue(received.get().path("messages").path(3).path("content").asText().contains("partnerRole"));
        assertThrows(IllegalArgumentException.class, () -> client.completeJson("A1", "x".repeat(65_000), Duration.ofSeconds(1)));
        assertThrows(IllegalStateException.class, () -> client.completeJson("A1", "{}", Duration.ZERO));
        assertEquals(1, requests.get());
    }

    @Test void assignsIdsKeepsSlotMetadataAndPreservesPublicContract() {
        content.set(ConversationGenerationPrompt.wireExample("A1", 2));
        var result = client.completeTask("A1", "{\"difficulty\":\"A1\"}", Duration.ofSeconds(30));
        assertEquals(2, result.slots().size());
        assertFalse(result.prompt().has("slots"));
        assertEquals(json.readTree(ConversationGenerationPrompt.example("A1", 2)), result.prompt());
        assertEquals(2, received.get().path("format").path("properties").path("facts").path("maxItems").asInt());
        assertEquals(2, received.get().path("format").path("properties").path("slots").path("maxItems").asInt());
        for (String level : List.of("A2", "B1"))
            assertEquals(3, ConversationLocalClient.schema(level).path("properties").path("facts").path("maxItems").asInt());
        var invalid = (tools.jackson.databind.node.ObjectNode) json.readTree(content.get());
        invalid.withArray("facts").remove(1);
        assertThrows(IllegalStateException.class, () -> ConversationLocalClient.normalizeOutput(invalid));
    }

    @Test void rejectsTrailingTokensTruncationOversizeAndRedirectsWithoutRetry() {
        for (String invalid : List.of("not JSON", "{} {}", "[]", "x".repeat(25_000))) {
            content.set(invalid); assertThrows(RuntimeException.class, this::call);
        }
        content.set("{}"); reason.set("length"); assertThrows(IllegalStateException.class, this::call);
        // Use a fresh client after exercising the five-failure circuit breaker.
        client = new ConversationLocalClient(json, new ConversationGenerationProperties("ollama",
                "http://127.0.0.1:" + server.getAddress().getPort(), null, 1), new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        reason.set("stop"); status.set(302); assertThrows(IllegalStateException.class, this::call);
        assertEquals(6, requests.get());
        status.set(200); assertTrue(call().isObject());
    }

    @Test void honorsRemainingDeadlineAcrossBodyAndReleasesSlot() throws Exception {
        gate.set(new CountDownLatch(1));
        Future<?> first = pool.submit(() -> assertThrows(IllegalStateException.class,
                () -> client.completeJson("A1", "{}", Duration.ofMillis(500))));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertThrows(ProviderGuard.Unavailable.class, this::call);
        first.get(2, TimeUnit.SECONDS);
        gate.get().countDown();
        assertTrue(call().isObject());
    }

    @Test void configRejectsRemoteTargetsAndCloudModels() {
        for (String url : List.of("relative", "https://example.com", "http://user@127.0.0.1", "http://127.0.0.1/path", "http://127.0.0.1?x=y"))
            assertThrows(IllegalArgumentException.class, () -> new ConversationGenerationProperties("ollama", url, null, 30));
        assertThrows(IllegalArgumentException.class, () -> new ConversationGenerationProperties("other", null, null, 30));
        assertThrows(IllegalArgumentException.class, () -> new ConversationGenerationProperties("ollama", null, "qwen:cloud", 30));
        assertThrows(IllegalArgumentException.class, () -> new ConversationGenerationProperties("ollama", null, "qwen:4b-cloud", 30));
        assertThrows(IllegalArgumentException.class, () -> new ConversationGenerationProperties("ollama", null, null, 601));
        assertThrows(IllegalArgumentException.class, () -> new ConversationGenerationProperties("ollama", null, null, -1));
        assertFalse(new ConversationGenerationProperties(null, null, null, 0).local());
    }

    @Test void hardwareTimeoutDefaultsAndLimitsIncludeRepairAndReviewBudget() {
        var defaults = new ConversationGenerationProperties("ollama", null, null, 0);
        assertEquals("qwen3.5:9b", defaults.localModel());
        assertEquals(180, defaults.localTimeoutSeconds());
        assertEquals(450, defaults.localFlowTimeoutSeconds());
        var slowMachine = new ConversationGenerationProperties("ollama", null, null, 600);
        assertEquals(1290, slowMachine.localFlowTimeoutSeconds());
        var slowClient = new ConversationLocalClient(json, slowMachine, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        assertEquals(1290, slowClient.flowTimeoutSeconds());
    }

    @Test void configuredPerCallLimitStillAppliesWithLongFlowBudget() {
        gate.set(new CountDownLatch(1));
        long started = System.nanoTime();
        assertThrows(IllegalStateException.class, this::call);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
        assertEquals(1, requests.get());
    }
}
