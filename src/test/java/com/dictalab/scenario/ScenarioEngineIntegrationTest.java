package com.dictalab.scenario;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static com.dictalab.scenario.ScenarioData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mock provider verdicts exercise domain safety, not pedagogical release approval. */
@SpringBootTest(properties={"dictalab.scenario.enabled=true","dictalab.scenario.engine.enabled=true","dictalab.scenario.engine.poll-delay-ms=600000"})
@AutoConfigureMockMvc
class ScenarioEngineIntegrationTest {
    @Autowired ScenarioEngine engine;
    @Autowired ScenarioOperations operations;
    @Autowired ScenarioData data;
    @Autowired ScenarioProgramRegistry registry;
    @Autowired ScenarioProgramStore store;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired AppUserRepository users;
    @Autowired MockMvc mvc;
    @MockitoBean AiPlatformClient ai;
    @MockitoBean AiUsageBudget budget;
    @MockitoBean ScenarioRelease release;
    @MockitoBean MediaStorageService media;
    AppUser owner;
    long user;
    JsonNode fixture;

    @BeforeEach void setup() throws Exception {
        store.run(null);
        owner=users.saveAndFlush(new AppUser(UUID.randomUUID()+"@scenario-engine.test","Learner","unused",Instant.now())); user=owner.getId();
        try (var input=getClass().getResourceAsStream("/scenario/workflow-example.json")) { fixture=ScenarioJson.MAPPER.readTree(input.readAllBytes()); }
        when(release.program()).thenReturn(registry.require("general-communication",1));
        when(release.promptHash()).thenReturn(new String(getClass().getResourceAsStream("/scenario/workflow-v1.sha256").readAllBytes()).trim());
        when(release.available()).thenReturn(true);
        when(release.available(anyLong())).thenReturn(true);
        when(ai.local()).thenReturn(true); when(ai.scenarioProvider()).thenReturn("ollama");
        when(ai.audio(any())).thenCallRealMethod();
        var accounting=new AiUsageBudget(db,manager,true,10000000,1000000,10,20,100000,1000);
        doAnswer(call -> { accounting.reserveScenario(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4)); return null; })
                .when(budget).reserveScenario(anyLong(),anyString(),anyInt(),anyBoolean(),anyBoolean());
        when(ai.submitScenario(anyLong(),anyString(),any(),any())).thenAnswer(call -> done(call.getArgument(1),call.getArgument(3)));
    }
    ScenarioEngine.DraftRequest input() { return new ScenarioEngine.DraftRequest("Tôi muốn đặt bánh để nhận ngày mai","general-communication",1,"supported"); }
    JsonNode done(String id,JsonNode input) {
        String phase=input.path("phase").asText(); boolean tts=input.path("kind").asText().equals("tts");
        var result=object().put("schemaVersion","1").put("generationId",id).put("requestId",id)
                .put("provider",tts ? "cartesia" : "ollama").put("model","fixture-model").put("modelDigest","fixture-digest")
                .put("promptVersion",tts ? "TTS_WAV_V1" : "SCENARIO_LISTENING_V3").put("promptHash",release.promptHash()).put("programHash",input.path("programHash").asText());
        if (tts) result.set("task",object().put("contentType","audio/wav").put("size",wave().length).put("audioBase64",Base64.getEncoder().encodeToString(wave())));
        else {
            result.set("task",fixture.path(switch(phase) { case "EXTRACT" -> "specification"; case "GENERATE" -> "content"; default -> "grade"; }).deepCopy());
            var calls=result.putArray("calls");
            for (String purpose : phase.equals("GENERATE") ? List.of("GENERATE","FACTS","QUESTIONS","TRANSLATE","REVIEW") : List.of(phase))
                calls.addObject().put("purpose",purpose).put("provider","ollama").put("model","fixture-model");
        }
        var state=object().put("jobId",id).put("schemaVersion","1").put("status","DONE"); state.set("result",result); return state;
    }
    static byte[] wave() {
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes())
                .putInt(16).putShort((short)1).putShort((short)1).putInt(24000).putInt(48000).putShort((short)2).putShort((short)16)
                .put("data".getBytes()).putInt(4).putInt(0).array();
    }
    JsonNode dispatch(JsonNode op) { String id=op.path("operationId").asText(); operations.dispatch(id); return operations.get(user,id); }
    JsonNode active() {
        var extraction=dispatch(engine.createDraft(user,id(),input()));
        var draft=engine.draft(user,extraction.path("draftId").asText());
        var generation=dispatch(engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong())));
        assertThat(generation.path("status").asText()).isEqualTo("SUCCEEDED");
        return engine.session(user,generation.path("sessionId").asText());
    }
    ScenarioEngine.AnswersRequest answers(JsonNode session) {
        return new ScenarioEngine.AnswersRequest(session.path("version").asLong(),List.of(
                new ScenarioEngine.Answer("q1",fixture.path("answers").get(0).path("textVi").asText()),
                new ScenarioEngine.Answer("q2",fixture.path("answers").get(1).path("textVi").asText())));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"The shop opens at nine.","Cửa hàng mở lúc chín giờ.","Cửa hàng opens at nine."})
    void gradingPreservesEnglishVietnameseAndMixedLearnerAnswers(String answer) {
        var session=active(); String sid=session.path("sessionId").asText(), aid=session.path("activities").get(0).path("activityId").asText();
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            ((ObjectNode)response.path("result").path("task").path("facts").get(0)).put("evidenceVi",answer);
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var request=new ScenarioEngine.AnswersRequest(session.path("version").asLong(),List.of(
                new ScenarioEngine.Answer("q1",answer),answers(session).answers().get(1)));
        var op=engine.answer(user,sid,aid,id(),request);
        assertThat(dispatch(op).path("status").asText()).isEqualTo("SUCCEEDED");
        verify(ai).submitScenario(eq(user),anyString(),any(),argThat(p -> "GRADE".equals(p.path("phase").asText())
                && answer.equals(p.path("answers").get(0).path("textVi").asText())));
        var stored=tree(db.queryForObject("SELECT answers_json FROM scenario_attempts WHERE activity_id=?",String.class,aid));
        assertThat(stored.get(0).path("textVi").asText()).isEqualTo(answer);
    }

    @Test void fullLifecycleKeepsKeysPrivateRecordsSupportCachesAudioAndRemixesWithoutOverwriting() throws Exception {
        JsonNode session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        verify(ai).submitScenario(eq(user),anyString(),any(),argThat(p -> "GENERATE".equals(p.path("phase").asText())
                && input().inputVi().equals(p.path("inputVi").asText())));
        assertThat(session.toString()).doesNotContain("we open at nine","Chín giờ","quoteEn","textEn","answerVi","provenance_json");
        var play=engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1"));
        String mediaId=dispatch(play).path("mediaId").asText();
        mvc.perform(get("/api/v2/scenarios/media/"+mediaId).with(user(DictaLabPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(content().bytes(wave())).andExpect(header().string("Cache-Control","no-store"));
        session=engine.session(user,sid); long reservations=db.queryForObject("SELECT COUNT(*) FROM scenario_operation_budget WHERE user_id=?",Long.class,user);
        var cached=engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1"));
        assertThat(cached.path("status").asText()).isEqualTo("READY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operation_budget WHERE user_id=?",Long.class,user)).isEqualTo(reservations);
        session=engine.session(user,sid);
        engine.help(user,sid,aid,id(),new ScenarioEngine.HelpRequest(session.path("version").asLong(),"TRANSCRIPT"));
        session=engine.session(user,sid); assertThat(session.toString()).contains("we open at nine").doesNotContain("answerVi");
        for (int ordinal=0; ordinal<3; ordinal++) {
            session=engine.session(user,sid); aid=session.path("activities").get(ordinal).path("activityId").asText();
            var op=engine.answer(user,sid,aid,id(),answers(session)); assertThat(dispatch(op).path("status").asText()).isEqualTo("SUCCEEDED");
            session=engine.session(user,sid); var attempt=session.path("activities").get(ordinal).path("attempts").get(0);
            assertThat(attempt.path("support").path("transcriptOpened").asBoolean()).isEqualTo(ordinal==0);
            session=engine.completeActivity(user,sid,aid,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong()));
        }
        assertThat(session.path("status").asText()).isEqualTo("COMPLETED"); assertThat(session.toString()).contains("answerVi");
        var remix=dispatch(engine.remix(user,sid,id(),new ScenarioEngine.RemixRequest(session.path("version").asLong(),"Đổi thành nhận bánh trong buổi chiều","supported")));
        assertThat(remix.path("draftId").asText()).isNotEqualTo(session.path("draftId").asText());
        assertThat(db.queryForObject("SELECT parent_session_id FROM scenario_drafts WHERE id=?",String.class,remix.path("draftId").asText())).isEqualTo(sid);
        assertThat(engine.session(user,sid).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test void retriesAndConcurrentDuplicateCreatesReserveOnlyOnceAndConflictingIntentFails() throws Exception {
        String key=id(); var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(() -> engine.createDraft(user,key,input())); var b=executor.submit(() -> engine.createDraft(user,key,input()));
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_drafts WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT calls_reserved FROM scenario_operation_budget WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThatThrownBy(() -> engine.createDraft(user,key,new ScenarioEngine.DraftRequest("Khác","general-communication",1,"supported")))
                .isInstanceOfSatisfying(ScenarioFailure.class,e -> assertThat(e.code).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(engine.createDraft(user,key,input()).path("operationId").asText()).isNotBlank();
    }

    @Test void budgetRejectionRollsBackDraftOperationAndAdmissionTogether() {
        doThrow(new AiUsageBudget.Rejected("Stopped")).when(budget).reserveScenario(anyLong(),anyString(),anyInt(),anyBoolean(),anyBoolean());
        assertThatThrownBy(() -> engine.createDraft(user,id(),input())).isInstanceOf(AiUsageBudget.Rejected.class);
        for (String table : List.of("scenario_drafts","scenario_operations","scenario_operation_budget"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,user)).isZero();
        verify(ai,never()).submitScenario(anyLong(),anyString(),any(),any());
    }

    @Test void endpointsAreCsrfProtectedStrictAndOwnerScopedIncludingPrivateAudio() throws Exception {
        mvc.perform(post("/api/v2/scenarios/drafts").with(user(DictaLabPrincipal.from(owner))).contentType("application/json").content(json(input()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/scenarios/drafts").with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                .contentType("application/json").content(json(input()).replace("\"supported\"","\"supported\",\"unknown\":true")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        JsonNode session=active(); var other=users.saveAndFlush(new AppUser(id()+"@other.test","Other","unused",Instant.now()));
        mvc.perform(get("/api/v2/scenarios/sessions/"+session.path("sessionId").asText()).with(user(DictaLabPrincipal.from(other))))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v2/scenarios/media/"+id()).with(user(DictaLabPrincipal.from(other)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/scenarios/operations/"+id())).andExpect(status().isUnauthorized());
    }

    @Test void staleVersionsFutureActivitiesAndUnsubmittedCompletionCannotMutate() {
        var session=active(); String sid=session.path("sessionId").asText(),first=session.path("activities").get(0).path("activityId").asText(),future=session.path("activities").get(1).path("activityId").asText();
        assertThatThrownBy(() -> engine.answer(user,sid,first,id(),new ScenarioEngine.AnswersRequest(0,answers(session).answers()))).isInstanceOf(ScenarioFailure.class);
        assertThatThrownBy(() -> engine.answer(user,sid,future,id(),answers(session))).isInstanceOf(ScenarioFailure.class);
        assertThatThrownBy(() -> engine.completeActivity(user,sid,first,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong()))).isInstanceOf(ScenarioFailure.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_attempts t JOIN scenario_activities a ON a.id=t.activity_id WHERE a.session_id=?",Integer.class,sid)).isZero();
    }

    @Test void invalidReviewOrEvidenceFailsPublicationAndTtsFailureLeavesSessionActive() {
        var extract=dispatch(engine.createDraft(user,id(),input())); var draft=engine.draft(user,extract.path("draftId").asText());
        doAnswer(call -> {
            var result=(ObjectNode)done(call.getArgument(1),call.getArgument(3)); ((ObjectNode)result.path("result").path("task")).put("qualityPassed",false); return result;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var generation=dispatch(engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong())));
        assertThat(generation.path("status").asText()).isEqualTo("FAILED");
        assertThat(engine.session(user,generation.path("sessionId").asText()).path("status").asText()).isEqualTo("PREPARATION_FAILED");
        doAnswer(call -> done(call.getArgument(1),call.getArgument(3))).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","UNKNOWN")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var audio=dispatch(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1")));
        assertThat(audio.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(engine.session(user,sid).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test void cancellationAndDeletedOwnerCannotPublishLateResults() {
        var op=engine.createDraft(user,id(),input()); String oid=op.path("operationId").asText();
        engine.cancel(user,oid,id(),new ScenarioEngine.VersionRequest(op.path("version").asLong())); operations.dispatch(oid);
        assertThat(operations.get(user,oid).path("status").asText()).isEqualTo("CANCELLED"); verify(ai,never()).submitScenario(anyLong(),anyString(),any(),any());
        var session=active(); var remixId=session.path("sessionId").asText();
        data.tx.executeWithoutResult(tx -> db.update("DELETE FROM app_users WHERE id=?",user));
        for (String table : List.of("scenario_drafts","scenario_sessions","scenario_operations","scenario_operation_budget","scenario_receipts"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,user)).isZero();
        assertThatThrownBy(() -> engine.session(user,remixId)).isInstanceOf(ScenarioFailure.class);
    }

    @Test void historyUsesStableKeysetAndRecommendationsDoNotInventMastery() {
        active(); active();
        var page=engine.history(user,null,1,null,null); assertThat(page.path("sessions").size()).isEqualTo(1);
        var next=engine.history(user,null,1,page.path("nextCursor").path("before").asText(),page.path("nextCursor").path("beforeId").asText());
        assertThat(next.path("sessions").get(0).path("sessionId")).isNotEqualTo(page.path("sessions").get(0).path("sessionId"));
        assertThat(engine.recommendations(user).path("basis").asText()).isEqualTo("COLD_START");
        assertThat(engine.recommendations(user).path("isAssessedScore").asBoolean()).isFalse();
    }

    @Test void failedBudgetLedgerInsertRollsBackDailyCountersAndAllDomainRows() {
        String constraint="scenario_budget_fixture_"+id().replace("-","");
        db.execute("ALTER TABLE scenario_operation_budget ADD CONSTRAINT "+constraint+" CHECK (user_id<>"+user+")");
        try {
            assertThatThrownBy(() -> engine.createDraft(user,id(),input())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            for (String table : List.of("scenario_drafts","scenario_operations","scenario_operation_budget"))
                assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Integer.class,user)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_daily_budget WHERE scope=?",Integer.class,"LOCAL_USER:"+user)).isZero();
        } finally { db.execute("ALTER TABLE scenario_operation_budget DROP CONSTRAINT "+constraint); }
    }

    @Test void generationReservesAllFiveCallsAndSharedLegacyQuotaCannotBeBypassed() {
        var extract=dispatch(engine.createDraft(user,id(),input())); var draft=engine.draft(user,extract.path("draftId").asText());
        for (int i=0;i<20;i++) db.update("INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json) VALUES (?,?,'cafe','A1','{}')",id(),user);
        assertThatThrownBy(() -> engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong())))
                .isInstanceOfSatisfying(ScenarioFailure.class,e -> assertThat(e.status).isEqualTo(429));
        db.update("DELETE FROM ai_conversation_sessions WHERE user_id=?",user);
        var op=engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong()));
        assertThat(db.queryForObject("SELECT calls_reserved FROM scenario_operation_budget WHERE operation_id=?",Integer.class,op.path("operationId").asText())).isEqualTo(5);
        assertThat(dispatch(op).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"skip_facts","wrong_order","old_version"})
    void currentWorkflowRejectsSkippedStagesAndVersionDowngrade(String fault) {
        var extract=dispatch(engine.createDraft(user,id(),input())); var draft=engine.draft(user,extract.path("draftId").asText());
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            var result=(ObjectNode)response.path("result");
            var calls=(tools.jackson.databind.node.ArrayNode)result.path("calls");
            if (fault.equals("skip_facts")) calls.remove(1);
            else if (fault.equals("wrong_order")) ((ObjectNode)calls.get(1)).put("purpose","QUESTIONS");
            else result.put("promptVersion","SCENARIO_LISTENING_V1");
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var op=engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong()));
        assertThat(dispatch(op).path("status").asText()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_activities WHERE session_id=?",Integer.class,op.path("sessionId").asText())).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
            "f3a1a93382df50b9e798d9ae9d11e502d3e8eb0c347751195c6d00b7ba88b8a1",
            "0d40fae31e57eef602c6f1e8402ea6116f8beeee56ac011c7c3d018f7f0152c2",
            "3391407dddc9762afe3774cb30ee60e4a9f0936e70597e8a057b8bdfe03395fe"})
    void alreadySubmittedJobCanSettleUnderItsOriginalPin(String oldPin) {
        var extract=dispatch(engine.createDraft(user,id(),input())); var draft=engine.draft(user,extract.path("draftId").asText());
        var op=engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong()));
        String oid=op.path("operationId").asText();
        var original=(ObjectNode)tree(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,oid));
        original.put("promptHash",oldPin);
        db.update("UPDATE scenario_operations SET payload_json=?,submitted=TRUE WHERE id=?",json(original),oid);
        var response=done(oid,original); var result=(ObjectNode)response.path("result");
        boolean previousV3=oldPin.equals("3391407dddc9762afe3774cb30ee60e4a9f0936e70597e8a057b8bdfe03395fe");
        result.put("promptHash",oldPin).put("promptVersion",previousV3 ? "SCENARIO_LISTENING_V3" : "SCENARIO_LISTENING_V1");
        var calls=result.putArray("calls");
        for (String purpose : previousV3 ? List.of("GENERATE","FACTS","QUESTIONS","TRANSLATE","REVIEW") : List.of("GENERATE","TRANSLATE","REVIEW"))
            calls.addObject().put("purpose",purpose).put("provider","ollama").put("model","fixture-model");
        when(ai.scenarioStatus(user,oid)).thenReturn(response);
        assertThat(dispatch(op).path("status").asText()).isEqualTo("SUCCEEDED");
        verify(ai,never()).submitScenario(eq(user),eq(oid),any(),any());
    }

    @Test void accountDeletionDuringProviderIoCannotRecreateLearningData() {
        var op=engine.createDraft(user,id(),input()); String oid=op.path("operationId").asText();
        doAnswer(call -> {
            data.tx.executeWithoutResult(tx -> db.update("DELETE FROM app_users WHERE id=?",user));
            return done(call.getArgument(1),call.getArgument(3));
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        operations.dispatch(oid);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE id=?",Integer.class,oid)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_drafts WHERE user_id=?",Integer.class,user)).isZero();
    }

    @Test void privateAudioCannotBeFetchedByAnotherOwnerAndNullInputsAreRejected() throws Exception {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        var audio=dispatch(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1")));
        var other=users.saveAndFlush(new AppUser(id()+"@other.test","Other","unused",Instant.now()));
        mvc.perform(get("/api/v2/scenarios/media/"+audio.path("mediaId").asText()).with(user(DictaLabPrincipal.from(other)))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v2/scenarios/drafts").with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                .contentType("application/json").content(json(input()).replace("\"supported\"","null")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void manualAudioRetriesAreBoundedAndUnknownNeverCreatesAnotherPaidJob() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","FAILED")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        for (int i=0;i<3;i++) {
            session=engine.session(user,sid);
            var op=engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1"));
            assertThat(dispatch(op).path("status").asText()).isEqualTo("FAILED");
        }
        session=engine.session(user,sid);
        engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=? AND kind='TTS'",Integer.class,user)).isEqualTo(3);
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","UNKNOWN")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        session=engine.session(user,sid);
        dispatch(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s2")));
        session=engine.session(user,sid);
        engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s2"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=? AND kind='TTS'",Integer.class,user)).isEqualTo(4);
    }
}
