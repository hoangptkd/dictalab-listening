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
    /** Responses returned for successive /api/chat calls (English-first, then translation). */
    final List<String> responses = new CopyOnWriteArrayList<>();
    /** All parsed request bodies received by the stub server, in order. */
    final List<JsonNode> receivedRequests = new CopyOnWriteArrayList<>();
    final AtomicReference<String> reason = new AtomicReference<>("stop");
    final AtomicInteger status = new AtomicInteger(200), requests = new AtomicInteger();
    final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(0));
    final CountDownLatch entered = new CountDownLatch(1);
    HttpServer server;
    ExecutorService pool;
    ConversationLocalClient client;

    /** Builds a minimal English-first response (no translationVi/textVi). */
    String englishResponse(String level, int turn) {
        var ex = json.readTree(ConversationGenerationPrompt.example(level, turn));
        var node = json.createObjectNode();
        // slots
        var slots = node.putArray("slots");
        var parts = ConversationGenerationPrompt.wireExampleEnglish(level, turn);
        var parsed = json.readTree(parts);
        node.set("slots", parsed.path("slots"));
        node.set("utteranceEn", ex.path("utteranceEn"));
        // facts with textEn (use evidenceEn as textEn)
        var facts = node.putArray("facts");
        for (var fact : ex.path("facts")) {
            var f = facts.addObject();
            f.set("textEn", fact.path("evidenceEn"));
            f.set("evidenceEn", fact.path("evidenceEn"));
        }
        node.set("questionVi", ex.path("questionVi"));
        node.set("taskVi", ex.path("taskVi"));
        node.set("skill", ex.path("skill"));
        return json.writeValueAsString(node);
    }

    /** Builds a minimal translation response for the given example. */
    String translateResponse(String level, int turn) {
        var ex = json.readTree(ConversationGenerationPrompt.example(level, turn));
        var node = json.createObjectNode();
        node.set("translationVi", ex.path("translationVi"));
        var facts = node.putArray("facts");
        for (var fact : ex.path("facts")) facts.addObject().set("textVi", fact.path("textVi"));
        return json.writeValueAsString(node);
    }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/api/chat", exchange -> {
            try {
                int idx = requests.getAndIncrement();
                receivedRequests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
                String responseContent = idx < responses.size() ? responses.get(idx) : responses.isEmpty() ? "{}" : responses.get(responses.size() - 1);
                byte[] bytes = json.writeValueAsString(Map.of("done", true, "done_reason", reason.get(),
                        "message", Map.of("content", responseContent))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                entered.countDown(); gate.get().await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        // Default: English response then translation response for A1 turn 2.
        responses.add(englishResponse("A1", 2));
        responses.add(translateResponse("A1", 2));
        client = new ConversationLocalClient(json, new ConversationGenerationProperties("ollama",
                "http://127.0.0.1:" + server.getAddress().getPort(), null, 1),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    @AfterEach void stop() { gate.get().countDown(); server.stop(0); pool.shutdownNow(); }

    JsonNode call() { return client.completeJson("A1", "{\"difficulty\":\"A1\"}", Duration.ofSeconds(450)); }

    @Test void sendsEnglishFirstRequestThenTranslationWithCorrectSchemas() {
        call();
        // Two requests: step 1 English-first, step 2 translation.
        assertEquals(2, requests.get());
        var eng = receivedRequests.get(0);
        var tr  = receivedRequests.get(1);

        // Step 1: English-first schema — required fields exclude translationVi, include textEn in facts.
        var engRequired = List.of("slots", "utteranceEn", "facts", "questionVi", "taskVi", "skill");
        assertEquals(engRequired, new ArrayList<>(eng.path("format").path("properties").propertyNames()));
        assertEquals(json.valueToTree(engRequired), eng.path("format").path("required"));
        assertEquals("textEn", eng.path("format").path("properties").path("facts")
                .path("items").path("properties").propertyNames().iterator().next());

        // Step 2: translation schema — required fields are translationVi and facts[{textVi}].
        var trRequired = List.of("translationVi", "facts");
        assertEquals(json.valueToTree(trRequired), tr.path("format").path("required"));
        assertEquals(0.1, tr.path("options").path("temperature").asDouble(), 0.001);

        // Step 1 uses English-first wire example as the assistant shot.
        assertEquals(ConversationGenerationPrompt.wireExampleEnglish("A1", 2),
                eng.path("messages").path(2).path("content").asText());

        // Step 2 sends utteranceEn, questionVi, facts with textEn, partnerRole, learnerRole.
        var trUser = json.readTree(tr.path("messages").path(1).path("content").asText());
        assertTrue(trUser.has("utteranceEn"));
        assertTrue(trUser.has("questionVi"));
        assertTrue(trUser.path("facts").path(0).has("textEn"));

        assertFalse(eng.path("think").asBoolean(true));
        assertFalse(eng.path("stream").asBoolean(true));
        assertEquals("object", eng.path("format").path("type").asText());
        assertThrows(IllegalArgumentException.class, () -> client.completeJson("A1", "x".repeat(65_000), Duration.ofSeconds(1)));
        assertThrows(IllegalStateException.class, () -> client.completeJson("A1", "{}", Duration.ZERO));
    }

    @Test void combinesMergesTranslationIntoEnglishOutput() {
        var result = client.completeTask("A1", "{\"difficulty\":\"A1\"}", Duration.ofSeconds(30));
        assertEquals(2, result.slots().size());
        assertFalse(result.prompt().has("slots"));
        // Final prompt must have translationVi and facts with textVi (not textEn).
        assertTrue(result.prompt().has("translationVi"));
        for (var fact : result.prompt().path("facts")) {
            assertTrue(fact.has("textVi"), "fact must have textVi after combine");
            assertFalse(fact.has("textEn"), "textEn must be removed after combine");
        }
        // A1 schema enforces maxItems=2.
        assertEquals(2, receivedRequests.get(0).path("format").path("properties").path("facts").path("maxItems").asInt());
        assertEquals(2, receivedRequests.get(0).path("format").path("properties").path("slots").path("maxItems").asInt());
        for (String level : List.of("A2", "B1"))
            assertEquals(3, ConversationLocalClient.schemaEnglish(level).path("properties").path("facts").path("maxItems").asInt());
    }

    @Test void rejectsTrailingTokensTruncationOversizeAndRedirectsWithoutRetry() {
        // Reset responses; each bad reply triggers an error on step 1.
        for (String invalid : List.of("not JSON", "{} {}", "[]", "x".repeat(25_000))) {
            responses.clear(); responses.add(invalid); responses.add(translateResponse("A1", 2));
            receivedRequests.clear(); requests.set(0);
            assertThrows(RuntimeException.class, this::call);
        }
        responses.clear(); responses.add("{}"); responses.add(translateResponse("A1", 2));
        receivedRequests.clear(); requests.set(0);
        reason.set("length"); assertThrows(IllegalStateException.class, this::call);
        // Use a fresh client after exercising the five-failure circuit breaker.
        client = new ConversationLocalClient(json, new ConversationGenerationProperties("ollama",
                "http://127.0.0.1:" + server.getAddress().getPort(), null, 1), new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        reason.set("stop"); status.set(302);
        responses.clear(); responses.add(englishResponse("A1", 2)); responses.add(translateResponse("A1", 2));
        receivedRequests.clear(); requests.set(0);
        assertThrows(IllegalStateException.class, this::call);
        status.set(200);
        responses.clear(); responses.add(englishResponse("A1", 2)); responses.add(translateResponse("A1", 2));
        receivedRequests.clear(); requests.set(0);
        assertTrue(call().isObject());
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
        // Only step 1 fires before the deadline; step 2 never reached.
        assertEquals(1, requests.get());
    }
}
