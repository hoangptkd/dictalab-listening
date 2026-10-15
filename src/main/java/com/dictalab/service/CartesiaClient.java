package com.dictalab.service;

import com.dictalab.config.ConversationProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class CartesiaClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ProviderGuard guard;
    private final String url;
    private final ObjectMapper json;
    private final ConversationProperties config;

    @org.springframework.beans.factory.annotation.Autowired
    public CartesiaClient(ObjectMapper json, ConversationProperties config,
            @org.springframework.beans.factory.annotation.Value("${dictalab.conversation.tts-concurrency:2}") int concurrency,
            @org.springframework.beans.factory.annotation.Value("${dictalab.conversation.tts-url:https://api.cartesia.ai/tts/bytes}") String url,
            io.micrometer.core.instrument.MeterRegistry metrics,
            io.micrometer.observation.ObservationRegistry observations,
            @org.springframework.beans.factory.annotation.Value("${dictalab.provider.circuit-failures:5}") int circuitFailures,
            @org.springframework.beans.factory.annotation.Value("${dictalab.provider.circuit-open-seconds:30}") int circuitOpenSeconds) {
        this.json = json;
        this.config = config;
        this.url = url;
        this.guard = new ProviderGuard("cartesia", concurrency, metrics,observations,circuitFailures,Duration.ofSeconds(circuitOpenSeconds));
    }

    public CartesiaClient(ObjectMapper json, ConversationProperties config) {
        this(json, config, 2, "https://api.cartesia.ai/tts/bytes", new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                io.micrometer.observation.ObservationRegistry.NOOP,5,30);
    }

    public boolean enabled() { return config.enabled() && !config.cartesiaApiKey().isBlank(); }

    public byte[] synthesize(String text) {
        if (!enabled() || text.isBlank() || text.length() > 1200) {
            throw new IllegalStateException("Audio unavailable");
        }
        try (var permit=guard.enter()) {
          try {
            var body = Map.of("model_id", config.cartesiaModel(), "transcript", text,
                    "voice", config.cartesiaVoice(), "language", "en",
                    "output_format", Map.of("container", "wav", "encoding", "pcm_s16le", "sample_rate", 24000));
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer " + config.cartesiaApiKey())
                    .header("Cartesia-Version", config.cartesiaVersion()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var pending = http.sendAsync(request, info -> BoundedProviderBody.bytes(3_000_000));
            try {
                var response = pending.get(45, TimeUnit.SECONDS);
                if (response.statusCode() != 200) {
                    if(response.statusCode()==429 || response.statusCode()>=500)permit.failure();
                    throw new IllegalStateException("Audio provider rejected request");
                }
                byte[] audio = response.body();
                if (audio.length < 44 || audio.length > 3_000_000
                        || audio[0] != 'R' || audio[1] != 'I' || audio[2] != 'F' || audio[3] != 'F'
                        || audio[8] != 'W' || audio[9] != 'A' || audio[10] != 'V' || audio[11] != 'E') {
                    throw new IllegalStateException("Invalid audio response");
                }
                permit.success();
                return audio;
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException exception) {
                permit.failure();
                throw new IllegalStateException("Audio unavailable");
            } finally {
                pending.cancel(true);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Audio interrupted");
          }
        }
    }
}
