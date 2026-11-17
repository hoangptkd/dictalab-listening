package com.dictalab.service;

import com.dictalab.config.TtsProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class TtsServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String url;
    private JsonNode input;
    private String version;
    private byte[] audio;
    private int status;
    private String contentType;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        audio = wav(); status = 200; contentType = "audio/wav";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/speech", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only-key");
            version = exchange.getRequestHeaders().getFirst("Cartesia-Version");
            input = json.readTree(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, audio.length);
            try (var output = exchange.getResponseBody()) { output.write(audio); }
        });
        server.start(); url = "http://127.0.0.1:" + server.getAddress().getPort() + "/speech";
    }
    @AfterEach void cleanup() { server.stop(0); }

    private TtsProperties config(String provider, boolean enabled) {
        return new TtsProperties(enabled, provider,
                new TtsProperties.Provider(url, "test-only-key", "sonic-3.6", "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4", "2026-08-14"),
                new TtsProperties.Provider(url, "test-only-key", "speech-model", "alloy", ""));
    }
    private TtsService service(TtsProperties config) {
        var transport = new TtsHttpTransport(json, 0, new SimpleMeterRegistry(), ObservationRegistry.NOOP, 5, 30);
        return new TtsService(config, List.of(new CartesiaClient(config, transport), new OpenAiCompatibleTtsClient(config, transport)));
    }

    @Test void cartesiaRemainsDefaultAndUsesItsOwnRequestContract() {
        assertThat(new TtsProperties(true, null, null, null).provider()).isEqualTo("cartesia");
        assertThat(service(config("cartesia", true)).synthesize("At nine.")).isEqualTo(audio);
        assertThat(input.path("transcript").asText()).isEqualTo("At nine.");
        assertThat(input.path("voice").path("mode").asText()).isEqualTo("id");
        assertThat(input.path("output_format").path("sample_rate").asInt()).isEqualTo(24000);
        assertThat(version).isEqualTo("2026-08-14");
    }

    @Test void selectingAnotherProviderChangesOnlyTheAdapter() {
        assertThat(service(config("openai_compatible", true)).synthesize("At nine.")).isEqualTo(audio);
        assertThat(input.path("input").asText()).isEqualTo("At nine.");
        assertThat(input.path("voice").asText()).isEqualTo("alloy");
        assertThat(input.path("model").asText()).isEqualTo("speech-model");
        assertThat(input.path("response_format").asText()).isEqualTo("wav");
        assertThat(input.has("transcript")).isFalse();
        assertThat(version).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void legacyConfigurationWorksWithConversationDisabled() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("spring.config.location=file:src/main/resources/application.yml", "spring.profiles.active=default",
                        "dictalab.conversation.enabled=false", "dictalab.conversation.cartesia-api-key=test-only-key",
                        "dictalab.conversation.tts-url=" + url)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var config = context.getBean(TtsProperties.class);
                    assertThat(config.enabled()).isTrue();
                    assertThat(service(config).synthesize("At nine.")).isEqualTo(audio);
                    assertThat(config.toString()).doesNotContain("test-only-key");
                });
    }

    @Test void disabledOrInvalidRequestsNeverReachAProvider() {
        var service = service(config("cartesia", false));
        assertThat(service.enabled()).isFalse();
        assertThatThrownBy(() -> service.synthesize("At nine.")).isInstanceOf(IllegalStateException.class);
        var enabled = service(config("cartesia", true));
        for (String text : new String[]{null, " ", "x".repeat(1201)})
            assertThatThrownBy(() -> enabled.synthesize(text)).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isZero();
    }

    @Test void rejectedOrMalformedResponsesAreNeverRetriedOrReplacedByAnotherProvider() {
        var service = service(config("openai_compatible", true));
        status = 429;
        assertThatThrownBy(() -> service.synthesize("At nine.")).isInstanceOf(IllegalStateException.class);
        status = 200; contentType = "application/json";
        assertThatThrownBy(() -> service.synthesize("At nine.")).isInstanceOf(IllegalStateException.class);
        contentType = "audio/wav"; audio = new byte[48];
        assertThatThrownBy(() -> service.synthesize("At nine.")).isInstanceOf(IllegalStateException.class);
        audio = wav(); ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48000);
        assertThatThrownBy(() -> service.synthesize("At nine.")).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test void onlyCompleteStreamingWavIsFinalizedAndPcmIsUnchanged() {
        byte[] expected = wav();
        ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -1).putInt(40, -1);
        assertThat(service(config("cartesia", true)).synthesize("At nine.")).isEqualTo(expected);
        assertThatThrownBy(() -> WavAudio.finalizeAndValidate(java.util.Arrays.copyOf(audio, 47)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void configurationRejectsUnknownProvidersInsecureUrlsAndHeaderInjection() {
        assertThatThrownBy(() -> config("unknown", true)).isInstanceOf(IllegalArgumentException.class);
        for (String unsafe : List.of("http://example.com/speech", "https://key@example.com/speech", "https://example.com/speech?key=secret"))
            assertThatThrownBy(() -> new TtsProperties.Provider(unsafe, "", "", "", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TtsProperties.Provider(url, "bad\r\nkey", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] wav() {
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(40).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16)
                .putInt(0x61746164).putInt(4).putInt(1234).array();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TtsProperties.class)
    static class PropertiesConfig {}
}
