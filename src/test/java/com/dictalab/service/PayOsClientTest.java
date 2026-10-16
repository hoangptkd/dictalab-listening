package com.dictalab.service;

import com.dictalab.config.BillingProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

class PayOsClientTest {
    private final ObjectMapper json=new ObjectMapper();
    private final BillingProperties config=new BillingProperties(true,49000,30,3,10,"https://dictalab.example","client","api","checksum");

    @Test void matchesThePublishedPayOsWebhookSignatureVector() {
        // Public example key and signature from payOS documentation; never live credentials.
        var example = new BillingProperties(true,49000,30,3,10,"https://dictalab.example","test","test",
                "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675");
        var payload=json.readTree("""
                {"orderCode":123,"amount":3000,"description":"VQRIO123","accountNumber":"12345678",
                "reference":"TF230204212323","transactionDateTime":"2023-02-04 18:25:00","currency":"VND",
                "paymentLinkId":"124c33293c43417ab7879e14c8d9eb18","code":"00","desc":"Thành công",
                "counterAccountBankId":"","counterAccountBankName":"","counterAccountName":"","counterAccountNumber":"",
                "virtualAccountName":"","virtualAccountNumber":""}
                """);
        assertThat(new PayOsClient(example,json).validSignature(payload,
                "412e915d2871504ed31be63c8f62a149a4410d34c4c42affc9006ef9917eaa03")).isTrue();
    }

    @Test void verifiesCanonicalSortedDataIncludingNullsAndRejectsAlteredMoney() throws Exception {
        var client=new PayOsClient(config,json);
        var payload=json.readTree("{\"orderCode\":123,\"amount\":49000,\"currency\":\"VND\",\"extra\":null,\"description\":\"DICTALAB\"}");
        Mac independent=Mac.getInstance("HmacSHA256");
        independent.init(new SecretKeySpec("checksum".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(independent.doFinal("amount=49000&currency=VND&description=DICTALAB&extra=&orderCode=123".getBytes(StandardCharsets.UTF_8)));
        assertThat(client.validSignature(payload,signature)).isTrue();
        assertThat(client.validSignature(payload,signature.toUpperCase())).isTrue();
        ((tools.jackson.databind.node.ObjectNode)payload).put("amount",49001);
        assertThat(client.validSignature(payload,signature)).isFalse();
        assertThat(client.validSignature(payload,"not-hex")).isFalse();
        assertThat(config.toString()).doesNotContain("checksum","client","api");
    }

    @Test void disabledConfigurationDoesNotMakePaymentsOrAcceptSignatures() {
        var disabled=new PayOsClient(new BillingProperties(false,49000,30,3,10,"","","",""),json);
        assertThat(disabled.validSignature(json.readTree("{}"),"0".repeat(64))).isFalse();
        assertThatThrownBy(()->disabled.create(1,49000,1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->new BillingProperties(true,49000,30,3,10,"http://example.com","a","b","c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void verifiesLookupWithEmptyTransactionsAndNullCancellationFields() throws Exception {
        var client=new PayOsClient(config,json);
        var payload=json.readTree("""
                {"id":"payment-link","orderCode":100000,"amount":49000,"amountPaid":0,"amountRemaining":49000,
                "status":"PENDING","createdAt":"2026-10-16T17:48:03.000Z","transactions":[],"canceledAt":null,"cancellationReason":null}
                """);
        String canonical="amount=49000&amountPaid=0&amountRemaining=49000&canceledAt=&cancellationReason=&createdAt=2026-10-16T17:48:03.000Z&id=payment-link&orderCode=100000&status=PENDING&transactions=[]";
        Mac independent=Mac.getInstance("HmacSHA256");
        independent.init(new SecretKeySpec("checksum".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(independent.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(client.validSignature(payload,signature)).isTrue();
        ((tools.jackson.databind.node.ObjectNode)payload).set("transactions",json.readTree("[{\"reference\":\"bank-ref\",\"amount\":49000}]"));
        canonical=canonical.replace("transactions=[]","transactions=[{\"amount\":49000,\"reference\":\"bank-ref\"}]");
        signature=HexFormat.of().formatHex(independent.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(client.validSignature(payload,signature)).isTrue();
    }
    @Test void fakeHttpProviderBoundsResponsesAndHonorsRetryAfterWithoutRetryingCreate() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v2/payment-requests",exchange->{
            calls.incrementAndGet();exchange.getResponseHeaders().add("Retry-After","9999");exchange.sendResponseHeaders(429,-1);exchange.close();
        });server.start();
        try {
            var client=new PayOsClient(config,json,java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            assertThatThrownBy(()->client.create(123,49000,1900000000)).isInstanceOfSatisfying(PayOsClient.RateLimited.class,e->assertThat(e.retryAfter()).isEqualTo(900));
            assertThat(calls).hasValue(1);
        }finally{server.stop(0);}
    }
    @Test void fakeHttpProviderRejectsInvalidSignatureMalformedAndOversizedBodies() throws Exception {
        var body=new java.util.concurrent.atomic.AtomicReference<>("{\"code\":\"00\",\"data\":{},\"signature\":\""+"0".repeat(64)+"\"}");
        var status=new java.util.concurrent.atomic.AtomicInteger(200);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v2/payment-requests",exchange->{byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}catch(java.io.IOException closed){}finally{exchange.close();}});server.start();
        try {
            var client=new PayOsClient(config,json,java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            for(String invalid:java.util.List.of(body.get(),"not-json","[]","null","x".repeat(140000))) {
                body.set(invalid);assertThatThrownBy(()->client.lookup(123)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            }
            status.set(503);body.set("unavailable");assertThatThrownBy(()->client.lookup(123)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }finally{server.stop(0);}
    }
}
