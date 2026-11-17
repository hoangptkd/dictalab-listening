package com.dictalab.service;

import com.dictalab.config.AiTextProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiTextProviderTest {
    final ObjectMapper json = new ObjectMapper();
    AiTextService.Request request(String mode) {
        return new AiTextService.Request(UUID.randomUUID(), "fixture_check", "default", "fixture-model",
            List.of(new AiTextService.Message("user", "Return JSON")), json.readTree("{\"type\":\"object\"}"),
            mode, 0.0, 240, Duration.ofSeconds(2), 8192, null);
    }
    @Test void registeredAdapterWorksWithoutBusinessChangesAndCarriesProvenance() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            var payload = json.readTree(exchange.getRequestBody().readAllBytes());
            assertThat(payload.has("max_tokens")).isFalse();
            assertThat(payload.has("temperature")).isFalse();
            assertThat(payload.path("max_completion_tokens").asInt()).isEqualTo(240);
            byte[] bytes = "{\"model\":\"fixture-revision\",\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            var properties = new com.dictalab.config.GeminiProviderProperties(List.of(
                new com.dictalab.config.GeminiProviderProperties.Provider("source", "account", "fake", "fixture-model",
                    url, 100, 0, 0, 100, true, 0)), 0, 0, true, "memory");
            var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            var quota = new GeminiQuotaCoordinator(properties, metrics, null, java.time.Clock.systemUTC());
            var registry = org.mockito.Mockito.mock(AiProviderRegistry.class);
            org.mockito.Mockito.when(registry.databaseEnabled()).thenReturn(true);
            org.mockito.Mockito.when(registry.properties("fixture")).thenReturn(properties);
            quota.configureRegistry(registry);
            var client = new AiTextService(json, "", "fixture-model", url, 0, metrics,
                io.micrometer.observation.ObservationRegistry.NOOP, 5, 30, properties, quota);
            client.configureRegistry(registry);
            client.configureLocal("fixture", "http://127.0.0.1:11434", "qwen3.5:9b");
            var fixture = new OpenAiCompatibleTextProvider() { @Override public String id() { return "fixture"; } };
            client.configureProviders(List.of(fixture), new AiTextProperties(
                "{\"fixture/fixture-model\":{\"tokenField\":\"max_completion_tokens\",\"temperaturePolicy\":\"omit\",\"jsonModes\":[\"json_object\"]}}"));
            var call = request("json_object");
            var result = client.complete(call);
            assertThat(result.requestId()).isEqualTo(call.requestId());
            assertThat(result.provider()).isEqualTo("fixture");
            assertThat(result.reportedModel()).isEqualTo("fixture-revision");
            assertThat(result.value().path("ok").asBoolean()).isTrue();
            assertThatThrownBy(() -> client.complete(request("json_schema")))
                .isInstanceOf(AiTextService.Failure.class).hasMessageContaining("Unsupported JSON mode");
            assertThat(calls.get()).isEqualTo(1);
            assertThatThrownBy(() -> client.configureProviders(List.of(fixture, fixture), new AiTextProperties("{}")))
                .isInstanceOf(IllegalArgumentException.class);
        } finally { server.stop(0); }
    }
    @Test void missingProviderAndUnsafeTargetsNeverSend() {
        var client = new AiTextService(json, "fake", "fixture-model", "http://127.0.0.1:1/v1/chat/completions");
        client.configureLocal("unknown", "http://127.0.0.1:11434", "qwen3.5:9b");
        assertThatThrownBy(() -> client.complete(request("json_object"))).isInstanceOf(AiTextService.Failure.class)
            .hasMessageContaining("not registered");
        assertThatThrownBy(() -> new OpenAiCompatibleTextProvider().validateTarget(
            new AiTextProvider.Target("https://user:secret@example.invalid/v1/chat/completions", "model", "fake", "source")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AiTextProvider.Target("https://secret.invalid", "model", "canary-secret", "source").toString())
            .doesNotContain("canary-secret", "secret.invalid");
    }
}
