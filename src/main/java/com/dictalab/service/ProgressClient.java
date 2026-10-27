package com.dictalab.service;

import com.dictalab.dto.ApiDtos;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ProgressClient {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1)).build();
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final String mode, baseUrl, token;
    private final int timeout;

    public ProgressClient(ObjectMapper json, MeterRegistry metrics,
            @Value("${dictalab.progress.read-mode:legacy}") String mode,
            @Value("${dictalab.progress.base-url:http://127.0.0.1:8092}") String baseUrl,
            @Value("${dictalab.progress.service-token:}") String token,
            @Value("${dictalab.progress.timeout-ms:2000}") int timeout,
            @Value("${dictalab.progress.enabled:false}") boolean enabled) {
        this.json = json; this.metrics = metrics; this.mode = mode;
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.token = token; this.timeout = timeout;
        if (!Set.of("legacy", "shadow", "remote").contains(mode) || timeout < 100 || timeout > 10000)
            throw new IllegalArgumentException("Invalid Progress read mode or timeout");
        if (!mode.equals("legacy")) {
            URI uri = URI.create(baseUrl);
            if (!enabled || token.length() < 32 || token.length() > 256 || !token.chars().allMatch(c -> c >= 33 && c <= 126)
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !Set.of("", "/").contains(uri.getPath()) || !(uri.getScheme().equals("https")
                    || uri.getScheme().equals("http") && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())))
                throw new IllegalArgumentException("Progress reads require publishing, service authentication, and TLS except loopback");
        }
    }

    public String mode() { return mode; }

    public Projection fetch(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid progress user");
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/users/" + userId + "/dashboard"))
                .timeout(Duration.ofMillis(timeout)).header("X-Service-Token", token)
                .header("X-Request-Id", UUID.randomUUID().toString()).GET().build();
        var pending = http.sendAsync(request, ignored -> BoundedProviderBody.bytes(64000));
        long start = System.nanoTime();
        String outcome = "error";
        try {
            var response = pending.get(timeout, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException("Progress service rejected request");
            Projection projection = json.readValue(response.body(), Projection.class);
            if (projection == null || projection.summary() == null || projection.trend() == null
                    || projection.byLevel() == null || projection.errors() == null
                    || projection.trend().size() > 8 || projection.byLevel().size() != 4 || !valid(projection))
                throw new IllegalStateException("Invalid Progress projection");
            outcome = "success";
            return projection;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Progress service is temporarily unavailable");
        } finally {
            pending.cancel(true);
            metrics.timer("dictalab.progress.http", "outcome", outcome).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private boolean valid(Projection p) {
        var s = p.summary();
        if (s.completedLessons() < 0 || s.learningMinutes() < 0 || p.errors().knownButMissed() < 0
                || p.errors().unknownWords() < 0 || !percentage(s.averageFirstAttemptAccuracy())
                || !percentage(s.averageFinalAccuracy()) || !percentage(s.averageScore())) return false;
        var levels = new java.util.HashSet<com.dictalab.domain.CefrLevel>();
        for (var level : p.byLevel()) {
            if (level == null || level.level() == null || !levels.add(level.level()) || level.completedLessons() < 0
                    || !percentage(level.averageFirstAttemptAccuracy()) || !percentage(level.averageScore())) return false;
        }
        for (var point : p.trend()) {
            if (point == null || point.date() == null || point.level() == null || point.lessonTitle() == null
                    || point.lessonTitle().length() > 500 || point.sessionId() == null
                    || !UUID.fromString(point.sessionId()).toString().equals(point.sessionId())
                    || !percentage(point.firstAttemptAccuracy()) || !percentage(point.finalAccuracy())
                    || !percentage(point.overallScore())) return false;
        }
        return true;
    }
    private boolean percentage(double value) { return Double.isFinite(value) && value >= 0 && value <= 100; }

    public void compare(long userId, ApiDtos.DashboardResponse local) {
        try {
            var remote = fetch(userId);
            boolean equal = remote.summary().equals(local.summary()) && remote.trend().equals(local.trend())
                    && remote.byLevel().equals(local.byLevel()) && remote.errors().equals(local.errors());
            metrics.counter("dictalab.progress.parity", "outcome", equal ? "match" : "mismatch").increment();
        } catch (ResponseStatusException failure) {
            metrics.counter("dictalab.progress.parity", "outcome", "unavailable").increment();
        }
    }

    public record Projection(ApiDtos.DashboardSummary summary, List<ApiDtos.TrendPoint> trend,
            List<ApiDtos.LevelProgress> byLevel, ApiDtos.ErrorBreakdown errors, Instant updatedAt, Instant lastEventAt) {}
}
