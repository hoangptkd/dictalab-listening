package com.dictalab.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class S3MediaConfigTest {
    private HttpServer server;
    private S3Client client;
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicBoolean fail = new AtomicBoolean(true);
    private final AtomicBoolean dropResponse = new AtomicBoolean();
    private final CopyOnWriteArrayList<byte[]> acceptedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (exchange.getRequestMethod().equals("PUT")) {
                    puts.incrementAndGet();
                    String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
                    if (encoding != null && encoding.contains("aws-chunked")) {
                        // The four-byte fixture has one data chunk; strip SDK signing/checksum framing.
                        String wire = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
                        int headerEnd = wire.indexOf("\r\n");
                        int length = Integer.parseInt(wire.substring(0, headerEnd).split(";", 2)[0], 16);
                        body = java.util.Arrays.copyOfRange(body, headerEnd + 2, headerEnd + 2 + length);
                    }
                    acceptedBodies.add(body); // Model the remote side effect before failure/response loss.
                } else if (exchange.getRequestMethod().equals("DELETE")) {
                    deletes.incrementAndGet();
                }
                if (dropResponse.get()) return;
                if (fail.get()) {
                    byte[] error = "<Error><Code>InternalError</Code><Message>Simulated failure after acceptance</Message></Error>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(503, error.length);
                    exchange.getResponseBody().write(error);
                } else {
                    exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
                    exchange.sendResponseHeaders(exchange.getRequestMethod().equals("DELETE") ? 204 : 200, -1);
                }
            }
        });
        server.start();
        var properties = new MediaStorageProperties(null, null,
                new MediaStorageProperties.S3(true, "audio-test", "us-east-1", null, null));
        // Copy the production bean's override configuration, replacing only endpoint/credentials for safety.
        try (var production = new S3MediaConfig().mediaS3Client(properties)) {
            client = S3Client.builder().region(Region.US_EAST_1)
                    .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                    .forcePathStyle(true)
                    .overrideConfiguration(production.serviceClientConfiguration().overrideConfiguration()).build();
        }
    }

    @AfterEach void tearDown() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test void keepsBoundedProductionDeadlines() {
        var configuration = client.serviceClientConfiguration().overrideConfiguration();
        assertThat(configuration.apiCallTimeout()).contains(Duration.ofSeconds(45));
        assertThat(configuration.apiCallAttemptTimeout()).contains(Duration.ofSeconds(15));
    }

    @Test void acceptedPutWithRetryableErrorIsNotSilentlyRepeated() {
        byte[] audio = new byte[]{'I', 'D', '3', 4};
        assertThatThrownBy(() -> put(audio)).isInstanceOf(SdkException.class);
        assertThat(puts).hasValue(1);
        assertThat(acceptedBodies).hasSize(1);
        assertThat(acceptedBodies.get(0)).isEqualTo(audio);
    }

    @Test void acceptedPutWithLostResponseIsNotSilentlyRepeatedAndNextRequestRecovers() {
        dropResponse.set(true);
        assertThatThrownBy(() -> put(new byte[]{'I', 'D', '3', 4})).isInstanceOf(SdkException.class);
        assertThat(puts).hasValue(1);
        dropResponse.set(false);
        fail.set(false);
        assertThatNoException().isThrownBy(() -> put(new byte[]{'I', 'D', '3', 5}));
        assertThat(puts).hasValue(2);
        assertThat(acceptedBodies).hasSize(2);
    }

    @Test void failedDeleteReturnsControlForDurableBackoffAndCanBeRetriedExplicitly() {
        var request = DeleteObjectRequest.builder().bucket("audio-test").key("dictalab/audio/fixture.mp3").build();
        assertThatThrownBy(() -> client.deleteObject(request)).isInstanceOf(SdkException.class);
        assertThat(deletes).hasValue(1);
        fail.set(false);
        assertThatNoException().isThrownBy(() -> client.deleteObject(request));
        assertThat(deletes).hasValue(2);
        assertThat(puts).hasValue(0);
    }

    private void put(byte[] body) {
        client.putObject(PutObjectRequest.builder().bucket("audio-test")
                .key("dictalab/audio/fixture.mp3").contentType("audio/mpeg").build(), RequestBody.fromBytes(body));
    }
}
