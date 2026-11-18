package com.dictalab.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class AiCacheMetricsTest {
    final ObjectMapper json = new ObjectMapper();
    ObjectNode run(String attempts) {
        var result = (ObjectNode)json.readTree("{\"cases\":[{\"after\":{\"status\":\"PASS\"},\"calls\":[{\"attempts\":" + attempts + "}]}]}");
        ((ObjectNode)result.path("cases").path(0).path("calls").path(0)).put("httpAttempts",result.path("cases").path(0).path("calls").path(0).path("attempts").size());
        return result;
    }
    @Test void cachedInputIsSubsetAndReasoningIsNotChargedTwice() {
        var run = run("[{\"model\":\"gemini-3.5-flash-lite\",\"input_tokens\":100,\"cached_input_tokens\":80,\"output_tokens\":10,\"reasoning_tokens\":6,\"seconds\":2}]");
        var result = AiCacheMetrics.summarize(json, run);
        assertThat(result.path("costComplete").asBoolean()).isTrue();
        assertThat(result.path("estimatedCostUsd").asDouble()).isCloseTo((20*.30+80*.03+10*2.50)/1e6, within(1e-12));
        assertThat(result.path("cachedInputRatio").asDouble()).isEqualTo(.8);
        assertThat(result.path("costPerJudgedPassUsd")).isEqualTo(result.path("estimatedCostUsd"));
    }
    @Test void missingCacheMeasurementsAndFailedFallbackAttemptsAreNotZeroCost() {
        var result = AiCacheMetrics.summarize(json, run("[{\"model\":\"gemini-3.5-flash-lite\",\"outcome\":\"failure\"},{\"model\":\"gemini-3.1-flash-lite\",\"input_tokens\":100,\"cached_input_tokens\":0,\"output_tokens\":10}]") );
        assertThat(result.path("costComplete").asBoolean()).isFalse();
        assertThat(result.path("estimatedCostUsd").isNull()).isTrue();
        assertThat(result.path("measuredCacheAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("pricedAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("hitRate").asDouble()).isZero();
        assertThat(result.path("measuredCostUsd").asDouble()).isCloseTo((100*.25+10*1.50)/1e6,within(1e-12));
    }
    @Test void unknownActualModelAndInvalidCacheNeverInheritARequestedPrice() {
        for (String attempt : new String[]{
            "{\"model\":\"gemini-3.5-flash-lite\",\"response_model\":\"unknown-version\",\"input_tokens\":100,\"cached_input_tokens\":80,\"output_tokens\":10}",
            "{\"model\":\"gemini-3.5-flash-lite\",\"input_tokens\":100,\"cached_input_tokens\":101,\"output_tokens\":10}"}) {
            var result = AiCacheMetrics.summarize(json, run("["+attempt+"]"));
            assertThat(result.path("estimatedCostUsd").isNull()).isTrue();
            assertThat(result.path("pricedAttempts").asInt()).isZero();
        }
    }
    @Test void historicalAggregateOnlyRecordsRemainReadableAndUnknown() {
        var historical = json.readTree("{\"cases\":[{\"calls\":[{\"prompt_tokens\":100,\"completion_tokens\":10}]}]}");
        var result = AiCacheMetrics.summarize(json, historical);
        assertThat(result.path("unobservedCalls").asInt()).isEqualTo(1);
        assertThat(result.path("hitRate").isNull()).isTrue();
        assertThat(result.path("estimatedCostUsd").isNull()).isTrue();
        assertThat(result.path("providerP95").isNull()).isTrue();
    }
    @Test void repeatedPrefixesAreGroupedBySourceAndModelWithoutAssumingCacheHits() {
        var run = run("[]"); var attempts = (tools.jackson.databind.node.ArrayNode)run.path("cases").path(0).path("calls").path(0).path("attempts");
        for (String source : new String[]{"a","a","b"}) attempts.addObject().put("source",source).put("model","gemini-3.5-flash-lite")
                .put("prefixHash","a".repeat(64)).put("seconds",1);
        var result = AiCacheMetrics.summarize(json,run);
        assertThat(result.path("distinctPrefixes").asInt()).isEqualTo(2);
        assertThat(result.path("repeatedPrefixAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("hitRate").isNull()).isTrue();
        assertThat(result.path("estimatedCostUsd").isNull()).isTrue();
    }
    @Test void truncatedAttemptListsCannotProduceACompleteCostClaim() {
        var run = run("[{\"model\":\"gemini-3.5-flash-lite\",\"input_tokens\":100,\"cached_input_tokens\":80,\"output_tokens\":10}]");
        ((ObjectNode)run.path("cases").path(0).path("calls").path(0)).put("httpAttempts",2);
        assertThat(AiCacheMetrics.summarize(json,run).path("estimatedCostUsd").isNull()).isTrue();
    }
    @Test void generationCostIsSeparatedFromJudgeCalls() {
        var run = run("[{\"model\":\"gemini-3.5-flash-lite\",\"input_tokens\":100,\"cached_input_tokens\":80,\"output_tokens\":10}]");
        var call = ((tools.jackson.databind.node.ArrayNode)run.path("cases").path(0).path("calls")).addObject().put("step","judge-before").put("httpAttempts",1);
        call.putArray("attempts").addObject().put("model","gemini-3.5-flash-lite").put("input_tokens",50).put("cached_input_tokens",0).put("output_tokens",10);
        var result = AiCacheMetrics.summarize(json,run);
        assertThat(result.path("generationAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("judgeAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("generationEstimatedCostUsd").asDouble()+result.path("judgeEstimatedCostUsd").asDouble())
                .isCloseTo(result.path("estimatedCostUsd").asDouble(),within(1e-12));
    }
}
