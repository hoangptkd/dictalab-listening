package com.dictalab.service;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class TracingExportTest {
    @Test
    void productionExportPropertiesExistInInstalledBootAndLogsAreDisabled() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        var config = yaml.getObject();
        var known = new java.util.HashSet<String>();
        var resources = getClass().getClassLoader().getResources("META-INF/spring-configuration-metadata.json");
        var json = new ObjectMapper();
        while (resources.hasMoreElements()) {
            try (var input = resources.nextElement().openStream()) {
                json.readTree(input).path("properties").forEach(property -> known.add(property.path("name").asText()));
            }
        }
        var keys = config.stringPropertyNames().stream().filter(key -> key.startsWith("management.opentelemetry.")
                || key.startsWith("management.logging.export.") || key.startsWith("management.tracing.export.")).toList();
        assertThat(known).containsAll(keys);
        assertThat(config.getProperty("management.logging.export.otlp.enabled")).isEqualTo("false");
        assertThat(config.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("${TRACE_EXPORT_ENABLED:false}");
        assertThat(config.getProperty("management.opentelemetry.tracing.export.max-queue-size")).isEqualTo("256");
        assertThat(config.getProperty("management.opentelemetry.tracing.export.otlp.timeout")).isEqualTo("2s");
    }

    @Test @Timeout(15)
    void providerFailureKeepsParentTraceAndCollectorFailureDoesNotBreakWork() throws Exception {
        var received = new CopyOnWriteArrayList<byte[]>();
        var spans = new CopyOnWriteArrayList<SpanData>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            received.add(exchange.getRequestBody().readNBytes(65536));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (var http = OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces")
                .setTimeout(Duration.ofSeconds(2)).build()) {
            SpanExporter recording = new SpanExporter() {
                public CompletableResultCode export(Collection<SpanData> batch) {
                    spans.addAll(batch); return http.export(batch);
                }
                public CompletableResultCode flush() { return http.flush(); }
                public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
            };
            var processor = BatchSpanProcessor.builder(recording).setMaxQueueSize(256)
                    .setMaxExportBatchSize(64).setScheduleDelay(Duration.ofMillis(20))
                    .setExporterTimeout(Duration.ofSeconds(2)).build();
            var metrics = new SimpleMeterRegistry();
            try (var sdk = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
                var tracer = new OtelTracer(sdk.get("dictalab-test"), new OtelCurrentTraceContext(), event -> {});
                var registry = ObservationRegistry.create();
                registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
                var guard = new ProviderGuard("fixture", 1, metrics, registry, 5, Duration.ofSeconds(30));
                var root = Observation.start("http.fixture", registry);
                try (var scope = root.openScope(); var permit = guard.enter()) {
                    permit.failure();
                } finally { root.stop(); }
                assertThat(sdk.forceFlush().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
                assertThat(received).isNotEmpty();
                assertThat(new String(received.get(0), StandardCharsets.ISO_8859_1)).contains("dictalab.provider.call");
                var parent = spans.stream().filter(s -> s.getName().equals("http.fixture")).findFirst().orElseThrow();
                var child = spans.stream().filter(s -> s.getName().equals("dictalab.provider.call")).findFirst().orElseThrow();
                assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
                assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId());
                assertThat(child.getEvents()).anyMatch(event -> event.getName().equals("exception"));
                assertThat(child.getAttributes().asMap().keySet()).allMatch(key -> List.of("provider").contains(key.getKey()));

                server.stop(0);
                // Export is asynchronous and bounded. An unavailable collector must not escape into business work.
                assertThatNoException().isThrownBy(() -> {
                    try (var permit = guard.enter()) { permit.success(); }
                });
                sdk.forceFlush().join(5, TimeUnit.SECONDS);
                assertThat(metrics.get("dictalab.provider.inflight").gauge().value()).isZero();
                assertThat(spans).hasSize(3);
                assertThat(tracer.currentSpan()).isNull();
            } finally { metrics.close(); }
        } finally { server.stop(0); }
    }
}
