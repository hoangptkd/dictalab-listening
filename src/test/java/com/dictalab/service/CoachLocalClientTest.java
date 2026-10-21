package com.dictalab.service;

import com.dictalab.config.CoachProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class CoachLocalClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> content = new AtomicReference<>("{\"focus\":null,\"reviewIds\":[]}");
    private final AtomicInteger status = new AtomicInteger(200), requests = new AtomicInteger();
    private final AtomicReference<Map<?, ?>> received = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> bodyGate = new AtomicReference<>(new CountDownLatch(0));
    private final AtomicReference<CountDownLatch> entered = new AtomicReference<>(new CountDownLatch(1));
    private HttpServer server;
    private ExecutorService pool;
    private CoachLocalClient client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/api/chat", exchange -> {
            try {
                requests.incrementAndGet();
                received.set(json.readValue(exchange.getRequestBody().readAllBytes(), Map.class));
                byte[] bytes = json.writeValueAsString(Map.of("done", true, "done_reason", "stop",
                        "message", Map.of("content", content.get()))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), bytes.length);
                entered.get().countDown();
                bodyGate.get().await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        client = new CoachLocalClient(json, new CoachProperties(true,"ollama",
                "http://127.0.0.1:" + server.getAddress().getPort(), "qwen3.5:4b", 1),
                new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    @AfterEach void stop() { bodyGate.get().countDown(); server.stop(0); pool.shutdownNow(); }

    @Test void sendsBoundedStructuredNonThinkingRequestAndParsesStrictJson() {
        assertTrue(client.completeJson("{\"level\":\"A1\",\"candidates\":[]}").path("focus").isNull());
        assertEquals(false, received.get().get("think"));
        assertEquals(false, received.get().get("stream"));
        assertEquals("qwen3.5:4b", received.get().get("model"));
        assertTrue(received.get().containsKey("format"));
        assertThrows(IllegalArgumentException.class, () -> client.completeJson("x".repeat(33_000)));
        assertEquals(1, requests.get());
    }

    @Test void rejectsInvalidJsonOversizeMissingModelAndReleasesSlot() {
        for (String invalid : java.util.List.of("not JSON", "{} {}", "x".repeat(17_000))) {
            content.set(invalid);
            assertThrows(RuntimeException.class, () -> client.completeJson("{}"));
        }
        status.set(404);
        assertThrows(IllegalStateException.class, () -> client.completeJson("{}"));
        status.set(200); content.set("{\"focus\":null,\"reviewIds\":[]}");
        assertTrue(client.completeJson("{}").isObject());
    }

    @Test void timesOutStalledBodyRejectsConcurrentRequestAndRecoversSlot() throws Exception {
        bodyGate.set(new CountDownLatch(1));
        Future<?> first = pool.submit(() -> assertThrows(IllegalStateException.class, () -> client.completeJson("{}")));
        assertTrue(entered.get().await(3, TimeUnit.SECONDS));
        assertThrows(ProviderGuard.Unavailable.class, () -> client.completeJson("{}"));
        assertEquals(1, requests.get());
        first.get(3, TimeUnit.SECONDS);
        bodyGate.get().countDown();
        assertTrue(client.completeJson("{}").isObject());
    }

    @Test void localConfigRejectsRemoteServersAndDeadlinesLongerThanWorkerLease() {
        for (String url : java.util.List.of("https://example.com", "http://user@127.0.0.1", "http://127.0.0.1/path"))
            assertThrows(IllegalArgumentException.class, () -> new CoachProperties(true,"ollama",url,"qwen3.5:4b",60));
        assertThrows(IllegalArgumentException.class, () -> new CoachProperties(true,"ollama",null,null,181));
        assertThrows(IllegalArgumentException.class, () -> new CoachProperties(true,"other",null,null,60));
    }
}
