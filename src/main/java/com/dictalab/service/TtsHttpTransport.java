package com.dictalab.service;

import com.dictalab.config.TtsProperties;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bounded transport with separate provider circuits; never replays an uncertain paid request. */
@Service
public class TtsHttpTransport {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final Map<String, ProviderGuard> guards;

    public TtsHttpTransport(ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${dictalab.tts.concurrency:0}") int concurrency,
            io.micrometer.core.instrument.MeterRegistry metrics,
            io.micrometer.observation.ObservationRegistry observations,
            @org.springframework.beans.factory.annotation.Value("${dictalab.provider.circuit-failures:5}") int failures,
            @org.springframework.beans.factory.annotation.Value("${dictalab.provider.circuit-open-seconds:30}") int openSeconds) {
        this.json = json;
        guards = Map.of("cartesia", new ProviderGuard("cartesia", concurrency, metrics, observations, failures, Duration.ofSeconds(openSeconds)),
                "openai_compatible", new ProviderGuard("openai_tts", concurrency, metrics, observations, failures, Duration.ofSeconds(openSeconds)));
    }

    byte[] synthesize(String provider, TtsProperties.Provider config, Map<String, Object> body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(URI.create(config.url())).timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + config.apiKey()).header("Content-Type", "application/json");
        headers.forEach(builder::header);
        var request = builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try (var permit = guards.get(provider).enter()) {
            var pending = http.sendAsync(request, ignored -> BoundedProviderBody.bytes(3_000_000));
            try {
                var response = pending.get(45, TimeUnit.SECONDS);
                if (response.statusCode() != 200) {
                    if (response.statusCode() == 429 || response.statusCode() >= 500) permit.failure();
                    throw new IllegalStateException("Audio provider rejected request");
                }
                String type = response.headers().firstValue("Content-Type").orElse("").split(";")[0].toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("audio/wav", "audio/x-wav", "audio/wave").contains(type))
                    throw new IllegalStateException("Invalid audio content type");
                byte[] audio = WavAudio.finalizeAndValidate(response.body());
                permit.success();
                return audio;
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException unavailable) {
                permit.failure();
                throw new IllegalStateException("Audio unavailable");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Audio interrupted");
            } finally { pending.cancel(true); }
        }
    }
}
