package com.dictalab.web;

import com.dictalab.config.AiPlatformProperties;
import com.dictalab.security.DictaLabPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class GeminiAdminControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DictaLabPrincipal admin = new DictaLabPrincipal(7L, "admin@test.local", "Admin", "unused", "ADMIN", true);

    @Test void proxiesBoundedMetadataAndWriteOnlyKeyWithTrustedActor() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicReference<String>();
        server.createContext("/v1/admin/gemini", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Provider-Admin-Token")).isEqualTo("m".repeat(40));
            assertThat(exchange.getRequestHeaders().getFirst("X-Admin-Actor")).isEqualTo("7");
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"revision\":2,\"applied\":\"new_requests\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try(var output = exchange.getResponseBody()) { output.write(reply); }
        });
        server.start();
        try {
            var config = new AiPlatformProperties("http://127.0.0.1:" + server.getAddress().getPort(), "s".repeat(40),
                "gemini", 30, "legacy", "legacy", "legacy", Set.of());
            var controller = new GeminiAdminController(config, json, "m".repeat(40), "python");
            assertThat(controller.save("source-a", json.readTree("{\"apiKey\":\"fake-only-key\"}"), admin).path("revision").asInt()).isEqualTo(2);
            assertThat(received.get()).contains("fake-only-key");
            assertThat(controller.snapshot(admin, "day", "source-a", null).toString()).doesNotContain("fake-only-key");
            assertThatThrownBy(() -> controller.snapshot(admin, "invalid", null, null)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> controller.save("../bad", json.readTree("{}"), admin)).isInstanceOf(ResponseStatusException.class);
        } finally { server.stop(0); }
    }

    @Test void rejectsUnconfiguredOrLegacyManagementBeforeNetwork() {
        var config = new AiPlatformProperties("http://127.0.0.1:1", "s".repeat(40), "gemini", 30, "legacy", "legacy", "legacy", Set.of());
        assertThatThrownBy(() -> new GeminiAdminController(config, json, "m".repeat(40), "legacy").snapshot(admin, "day", null, null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
    }
}
