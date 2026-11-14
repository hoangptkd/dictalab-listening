package com.dictalab.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiCallObservationTest {
    @Test void observesEverySourceAttemptAndDoesNotInventMissingUsage() {
        var calls=new ArrayList<Map<String,Object>>();var json=new ObjectMapper();
        assertThat(AiCallObservation.active()).isFalse();
        try(var scope=new AiCallObservation(()->{},calls::add)) {
            AiCallObservation.begin("gemini-test");
            AiCallObservation.http("source-a","gemini-test",0);AiCallObservation.status(429);
            AiCallObservation.http("source-b","gemini-test",0);AiCallObservation.status(200);
            AiCallObservation.usage(json.readTree("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"));
            AiCallObservation.end("SUCCESS");
        }
        assertThat(calls).hasSize(1);assertThat(calls.get(0).get("httpAttempts")).isEqualTo(2);
        assertThat((List<?>)calls.get(0).get("attempts")).hasSize(2);
        assertThat(calls.get(0)).doesNotContainKey("prompt_tokens");
        assertThat(AiCallObservation.active()).isFalse();
    }
    @Test void remoteUnknownAndCompleteUsageAreDistinct() {
        var calls=new ArrayList<Map<String,Object>>();var json=new ObjectMapper();
        try(var scope=new AiCallObservation(()->{},calls::add)) {
            AiCallObservation.begin("gemini-test");AiCallObservation.remoteStarted();AiCallObservation.end("AI_SERVICE");
            AiCallObservation.begin("gemini-test");AiCallObservation.remote(json.readTree("{\"observation\":{\"httpAttempts\":1,\"prompt_tokens\":9,\"completion_tokens\":3,\"attempts\":[]}}"));AiCallObservation.end("SUCCESS");
        }
        assertThat(calls.get(0).get("httpAttempts")).isNull();
        assertThat(calls.get(1)).containsEntry("prompt_tokens",9L).containsEntry("completion_tokens",3L).containsEntry("httpAttempts",1);
    }
    @Test void retainsActualReturnedModelAfterCallForExactJudgeCacheIdentity() {
        var calls = new ArrayList<Map<String,Object>>(); var json = new ObjectMapper();
        try (var scope = new AiCallObservation(() -> {}, calls::add)) {
            AiCallObservation.begin("primary");
            AiCallObservation.remote(json.readTree("{\"model\":\"fallback\",\"fallbacks\":[{\"fromModel\":\"primary\",\"toModel\":\"fallback\",\"httpStatus\":503}]}"));
            AiCallObservation.end("SUCCESS");
            assertThat(AiCallObservation.returnedModel()).isEqualTo("fallback");
            assertThat(calls.get(0)).containsEntry("model", "fallback").containsKey("fallbacks");
            AiCallObservation.begin("next");
            assertThat(AiCallObservation.returnedModel()).isNull();
            AiCallObservation.end("AI_SERVICE");
        }
        assertThat(AiCallObservation.returnedModel()).isNull();
    }
}
