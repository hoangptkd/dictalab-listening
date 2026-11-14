package com.dictalab.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiEvaluationStudioTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final ConversationService conversation = mock(ConversationService.class);
    final GeminiClient ai = mock(GeminiClient.class);
    final AiUsageBudget budget = mock(AiUsageBudget.class);
    AiEvaluationStudio studio;

    @BeforeEach void setup() {
        var fixtureSource = mock(ConversationService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(fixtureSource, "json", json);
        var fixtures = fixtureSource.evaluationFixtures();
        when(conversation.evaluationFixtures()).thenReturn(fixtures);
        when(conversation.evaluationModel()).thenReturn("gemini-test");
        when(conversation.evaluationAvailable()).thenReturn(true);
        studio = create();
    }
    AiEvaluationStudio create() {
        return new AiEvaluationStudio(json, conversation, ai, budget,
                new MockEnvironment().withProperty("spring.profiles.active", "local"), directory.toString(), "legacy");
    }
    @AfterEach void close() { if (studio != null) studio.close(); }
    ObjectNode version() { return studio.createVersion(json.createObjectNode().put("label", "Baseline").put("promptSuffix", "").put("repair", true), 14); }
    ObjectNode request(String version, String suite, boolean judge) {
        return json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("version", version)
                .put("suite", suite).put("mode", "live").put("judge", judge);
    }
    ObjectNode finished(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        ObjectNode run;
        do { run = studio.detail(id); if (!run.path("status").asText().equals("RUNNING")) return run; Thread.sleep(10); }
        while (System.nanoTime() < deadline);
        throw new AssertionError("Evaluation did not finish");
    }
    void fakeGeneration() {
        when(conversation.evaluateFixture(anyLong(), any(), anyString(), anyBoolean(), any())).thenAnswer(call -> {
            var raw = json.createObjectNode().put("utteranceEn", "Source evidence");
            ConversationService.GenerationObserver observer = call.getArgument(4);
            observer.record("generated", 1, raw); observer.record("validation", 1, json.createObjectNode().put("valid", true));
            return raw;
        });
    }
    ObjectNode verdict() {
        var value = json.createObjectNode(); var checks = value.putObject("checks");
        AiEvaluationStudio.CRITERIA.forEach(c -> checks.put(c, true)); value.putArray("issues"); return value;
    }

    @Test void snapshotsAreImmutableCanonicalAndDatasetCoversAllSuiteLevelsAndTurns() {
        var first = version(); var second = version();
        assertThat(json.writeValueAsString(second)).isEqualTo(json.writeValueAsString(first));
        assertThat(studio.hash(json.readTree("{\"b\":2,\"a\":1}"))).isEqualTo(studio.hash(json.readTree("{\"a\":1,\"b\":2}")));
        assertThat(first.path("instructions").size()).isEqualTo(9);
        assertThat(studio.snapshot().path("dataset").path("cases").asInt()).isEqualTo(27);
        assertThat(studio.snapshot().path("suites").path(2).path("samples").asInt()).isEqualTo(81);
        assertThatThrownBy(() -> studio.createVersion(json.readTree("{\"label\":\"x\",\"promptSuffix\":17,\"repair\":true}"),14))
                .hasMessageContaining("400");
        assertThatThrownBy(() -> studio.detail("../secrets")).hasMessageContaining("400");
    }
    @Test void smokeAndReplayUseSameInputsIdempotencyAndNoImplicitJudgeCalls() throws Exception {
        fakeGeneration(); var request = request(version().path("id").asText(), "smoke", false);
        var started = studio.start(request, 14); var run = finished(started.path("id").asText());
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("cases").size()).isEqualTo(3);
        assertThat(run.path("metrics").path("afterPassed").asInt()).isZero();
        assertThat(run.path("metrics").path("afterRate").isNull()).isTrue();
        assertThat(run.path("cases").path(0).path("after").path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        studio.start(request,14); verify(conversation,times(3)).evaluateFixture(eq(14L), any(), anyString(), eq(true), any());
        request.put("judge",true); assertThatThrownBy(() -> studio.start(request,14)).hasMessageContaining("409");
        var replay = request(version().path("id").asText(),"smoke",false).put("mode","replay").put("sourceRun",run.path("id").asText());
        var replayRun = finished(studio.start(replay,14).path("id").asText());
        assertThat(replayRun.path("datasetHash")).isEqualTo(run.path("datasetHash"));
        assertThat(replayRun.path("cases").path(0).path("harnessScope").asText()).isEqualTo("deterministic-only");
        assertThat(replayRun.path("metrics").path("harnessAccepted").asInt()).isZero();
        verifyNoInteractions(ai, budget);
    }
    @Test void exactJudgeCacheIsReusedAndUnknownFailureStopsFurtherCases() throws Exception {
        fakeGeneration(); when(ai.completeJson(anyString(),anyString(),eq("gemini-test"),any(Duration.class))).thenReturn(verdict());
        var version = version().path("id").asText();
        for (int i=0;i<2;i++) {
            var run = finished(studio.start(request(version,"smoke",true),14).path("id").asText());
            assertThat(run.path("metrics").path("afterPassed").asInt()).isEqualTo(3);
        }
        verify(ai,times(3)).completeJson(anyString(),anyString(),eq("gemini-test"),any(Duration.class));
        verify(budget,times(3)).reserve(14L,false);
        reset(conversation); when(conversation.evaluationModel()).thenReturn("gemini-test");
        var fixtureSource=mock(ConversationService.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(fixtureSource,"json",json);
        var fixtures = fixtureSource.evaluationFixtures();
        when(conversation.evaluationFixtures()).thenReturn(fixtures);when(conversation.evaluationAvailable()).thenReturn(true);
        when(conversation.evaluateFixture(anyLong(),any(),anyString(),anyBoolean(),any())).thenAnswer(call -> {
            ConversationService.GenerationObserver observer=call.getArgument(4);
            observer.record("generated",1,json.createObjectNode());
            observer.record("rejected",1,json.createObjectNode().put("reason","invalid content"));
            throw new GeminiClient.Failure("TIMEOUT","unknown");
        });
        var stopped=finished(studio.start(request(version,"regression",false),14).path("id").asText());
        assertThat(stopped.path("status").asText()).isEqualTo("STOPPED");
        assertThat(stopped.path("cases").size()).isEqualTo(1);
        verify(conversation).evaluateFixture(anyLong(),any(),anyString(),anyBoolean(),any());
    }
    @Test void malformedOrUnsubstantiatedJudgeVerdictCanNeverPass() {
        var input=json.createObjectNode().put("task","Source evidence"); var answer=verdict();
        assertThat(studio.checkedVerdict(answer,input).path("status").asText()).isEqualTo("PASS");
        ((ObjectNode)answer.path("checks")).put("answerable",false);
        assertThat(studio.checkedVerdict(answer,input).path("validVerdict").asBoolean()).isFalse();
        ((ArrayNode)answer.path("issues")).addObject().put("criterion","answerable").put("severity","major").put("evidence","invented").put("reason","unsupported");
        assertThat(studio.checkedVerdict(answer,input).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        ((ObjectNode)answer.path("issues").path(0)).put("evidence","Source evidence");
        var result=studio.checkedVerdict(answer,input);
        assertThat(result.path("status").asText()).isEqualTo("FAIL"); assertThat(result.path("critical").asBoolean()).isTrue();
    }
    @Test void stopPreventsNextCaseAndActiveRunRejectsConcurrentStart() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(conversation.evaluateFixture(anyLong(),any(),anyString(),anyBoolean(),any())).thenAnswer(call->{
            entered.countDown();assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return json.createObjectNode();
        });
        var version=version().path("id").asText();var run=studio.start(request(version,"smoke",false),14);
        try {
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->studio.start(request(version,"smoke",false),14)).hasMessageContaining("409");
            studio.stop(run.path("id").asText());
        } finally {release.countDown();}
        var stopped=finished(run.path("id").asText());
        assertThat(stopped.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(stopped.path("cases").size()).isEqualTo(1);
        verify(conversation).evaluateFixture(anyLong(),any(),anyString(),anyBoolean(),any());
    }
    @Test void interruptedRunIsRecoveredWithoutReplayAndProductionCannotInitialize() throws Exception {
        studio.snapshot(); var id=UUID.randomUUID().toString();
        Files.writeString(directory.resolve("runs").resolve(id+".json"),json.writeValueAsString(json.createObjectNode().put("id",id).put("status","RUNNING")));
        try(var ignored = new AiCallObservation(() -> {}, call -> {})) {
            var recovered=create(); try { assertThat(recovered.detail(id).path("status").asText()).isEqualTo("INTERRUPTED"); } finally {recovered.close();}
        }
        var production=new AiEvaluationStudio(json,conversation,ai,budget,new MockEnvironment(),directory.toString(),"legacy");
        try { assertThatThrownBy(production::snapshot).hasMessageContaining("503"); } finally { production.close(); }
        verifyNoInteractions(ai,budget);
    }
    @Test void missingJudgmentsRemainInDenominatorAndCriticalRegressionWinsOverOtherImprovements() {
        var a=comparableRun();var b=a.deepCopy();
        var av=(ObjectNode)a.path("cases").path(0).path("after");var bv=(ObjectNode)b.path("cases").path(0).path("after");
        av.put("status","FAIL"); ((ObjectNode)av.path("checks")).put("noAnswerLeak",false);
        bv.put("status","FAIL").put("critical",true);((ObjectNode)bv.path("checks")).put("answerable",false);
        var result=AiEvaluationStudio.comparison(json,a,b);
        assertThat(result.path("counts").path("regressed").asInt()).isEqualTo(1);
        assertThat(result.path("criticalRegression").asBoolean()).isTrue();
        assertThat(result.path("decision").asText()).isEqualTo("REGRESSION");
        b.remove("runtime");assertThat(AiEvaluationStudio.comparison(json,a,b).path("comparable").asBoolean()).isFalse();
        a.put("planned",3);a.path("cases").path(0).deepCopy();
        var metric=AiEvaluationStudio.metrics(json,a);
        assertThat(metric.path("afterRate").asDouble()).isZero();assertThat(metric.path("afterEvaluated").asInt()).isEqualTo(1);
        assertThat(AiEvaluationStudio.comparison(json,json.createObjectNode(),json.createObjectNode()).path("comparable").asBoolean()).isFalse();
    }
    @Test void personalizedVersionsPinPythonAndUseRealPipelineWithoutLegacyGenerator() throws Exception {
        var platform = mock(AiPlatformClient.class);
        ReflectionTestUtils.setField(studio, "platform", platform);
        ReflectionTestUtils.setField(studio, "programs", new com.dictalab.scenario.ScenarioProgramRegistry());
        var catalog = json.createObjectNode().put("backendHash", "python-build");
        catalog.putObject("runtime").put("provider", "gemini").put("model", "gemini-test");
        var release = catalog.putArray("releases").addObject().put("id", "v4.13").put("promptVersion", "V4.13").put("promptHash", "pin");
        release.putObject("instructions").put("GENERATE", "Original immutable prompt");
        when(platform.scenarioEvaluation(isNull(), isNull())).thenReturn(catalog);
        when(platform.scenarioEvaluation(isNull(), argThat(n -> n != null && n.has("payload")))).thenAnswer(call -> {
            JsonNode input = call.getArgument(1);
            assertThat(input.path("payload").path("promptHash").asText()).isEqualTo("pin");
            assertThat(input.path("payload").path("profile").size()).isEqualTo(4);
            var remote = json.createObjectNode().put("requestId", input.path("requestId").asText()).put("status", "COMPLETED");
            var raw = json.createObjectNode().putArray("activities"); raw.addObject().put("id", "a1");
            remote.putObject("before").put("hardPass", true).set("raw", json.createObjectNode().set("activities", raw));
            remote.putObject("result").put("promptHash", "pin").set("task", json.createObjectNode().put("qualityPassed", true).set("activities", raw));
            var event = remote.putArray("events").addObject().put("stage", "GENERATE").put("status", "RETURNED").put("model", "fallback-model");
            event.putArray("attempts").addObject().put("model", "fallback-model").put("prompt_tokens", 10).put("completion_tokens", 5);
            return remote;
        });
        var request = json.createObjectNode().put("pipeline", "scenario").put("release", "v4.13").put("label", "Personalized");
        var version = studio.createVersion(request, 14);
        assertThat(version.path("pythonBackendHash").asText()).isEqualTo("python-build");
        assertThat(studio.snapshot().path("scenarioPipeline").path("fixtures").asInt()).isEqualTo(12);
        var run = finished(studio.start(request(version.path("id").asText(), "smoke", false), 14).path("id").asText());
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("pipeline").asText()).isEqualTo("scenario");
        assertThat(run.path("cases").size()).isEqualTo(3);
        assertThat(run.path("cases").path(0).path("calls").path(0).path("model").asText()).isEqualTo("fallback-model");
        assertThat(run.path("metrics").path("afterEvaluated").asInt()).isZero();
        assertThat(run.path("metrics").path("harnessAccepted").asInt()).isEqualTo(3);
        verify(conversation, never()).evaluateFixture(anyLong(), any(), anyString(), anyBoolean(), any());
        catalog.put("backendHash", "changed");
        assertThatThrownBy(() -> studio.start(request(version.path("id").asText(), "smoke", false), 14)).hasMessageContaining("409");
        var other = run.deepCopy(); ((ObjectNode)other.path("cases").path(0).path("calls").path(0)).put("model", "different");
        assertThat(AiEvaluationStudio.comparison(json, run, other).path("comparable").asBoolean()).isFalse();
    }
    ObjectNode comparableRun() {
        var run=json.createObjectNode().put("status","COMPLETED").put("planned",1).put("repeats",1).put("judge",true).put("mode","live").put("repair",true);
        for(String field:List.of("datasetHash","rubric","judgeHash","promptHash","backendHash"))run.put(field,"same");
        run.putObject("runtime").put("model","gemini-test");var row=run.putArray("cases").addObject().put("key","case:1");
        var after=row.putObject("after").put("status","PASS");var checks=after.putObject("checks");AiEvaluationStudio.CRITERIA.forEach(c->checks.put(c,true));return run;
    }
}
