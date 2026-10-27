package com.dictalab.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ProgressClientTest {
    HttpServer server;
    String base;
    final String token = "progress-unit-test-token-at-least-32-chars";
    final AtomicReference<String> seenPath = new AtomicReference<>();
    final AtomicReference<String> seenToken = new AtomicReference<>();
    final AtomicReference<String> response = new AtomicReference<>("""
            {"summary":{"completedLessons":0,"learningMinutes":0,"averageFirstAttemptAccuracy":0,"averageFinalAccuracy":0,"averageScore":0},
             "trend":[],"byLevel":[{"level":"A1","completedLessons":0,"averageFirstAttemptAccuracy":0,"averageScore":0},
             {"level":"A2","completedLessons":0,"averageFirstAttemptAccuracy":0,"averageScore":0},
             {"level":"B1","completedLessons":0,"averageFirstAttemptAccuracy":0,"averageScore":0},
             {"level":"B2","completedLessons":0,"averageFirstAttemptAccuracy":0,"averageScore":0}],
             "errors":{"knownButMissed":0,"unknownWords":0},"updatedAt":null,"lastEventAt":null}
            """);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); base = "http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    ProgressClient client() { return new ProgressClient(JsonMapper.builder().build(), new SimpleMeterRegistry(), "remote", base, token, 1000, true); }

    @Test void sendsOnlyAuthorizedUserScopeAndServiceCredential() {
        assertThat(client().fetch(123).summary().completedLessons()).isZero();
        assertThat(seenPath.get()).isEqualTo("/internal/v1/users/123/dashboard");
        assertThat(seenToken.get()).isEqualTo(token);
    }
    @Test void rejectsMalformedAndOversizedResponses() {
        String valid = response.get();
        response.set(valid.replace("\"completedLessons\":0", "\"completedLessons\":-1"));
        assertThatThrownBy(() -> client().fetch(1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        response.set(valid.replace("\"level\":\"B2\"", "\"level\":\"A1\""));
        assertThatThrownBy(() -> client().fetch(1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        response.set("{}");
        assertThatThrownBy(() -> client().fetch(1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        response.set(" ".repeat(64001));
        assertThatThrownBy(() -> client().fetch(1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void refusesInsecureOrUnauthenticatedCutoverConfiguration() {
        var json = JsonMapper.builder().build();
        var metrics = new SimpleMeterRegistry();
        assertThatThrownBy(() -> new ProgressClient(json,metrics,"remote","http://example.com",token,1000,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProgressClient(json,metrics,"remote",base,"",1000,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProgressClient(json,metrics,"remote",base,token,1000,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProgressClient(json,metrics,"unknown",base,token,1000,true)).isInstanceOf(IllegalArgumentException.class);
    }
}
