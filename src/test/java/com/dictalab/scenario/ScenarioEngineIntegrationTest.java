package com.dictalab.scenario;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@SpringBootTest(properties={"dictalab.scenario.enabled=true","dictalab.scenario.engine.enabled=true","dictalab.scenario.engine.poll-delay-ms=600000","dictalab.operational-limits-enabled=true"})
@AutoConfigureMockMvc
class ScenarioEngineIntegrationTest {
    @Autowired ScenarioEngine engine;
    @Autowired ScenarioOperations operations;
    @Autowired ScenarioData data;
    @Autowired ScenarioProgramRegistry registry;
    @Autowired ScenarioProgramStore store;
    @Autowired ScenarioPersonalization personalization;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired AppUserRepository users;
    @Autowired MockMvc mvc;
    @MockitoBean AiPlatformClient ai;
    @MockitoBean AiUsageBudget budget;
    @MockitoBean ScenarioRelease release;
    @MockitoBean MediaStorageService media;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ScenarioEngineProperties engineConfig;
    AppUser owner;
    long user;
    JsonNode fixture;
    String meaningGradeStatus="UNASSESSED";

    @BeforeEach void setup() throws Exception {
        store.run(null);
        owner=users.saveAndFlush(new AppUser(UUID.randomUUID()+"@scenario-engine.test","Learner","unused",Instant.now())); user=owner.getId();
        try (var input=getClass().getResourceAsStream("/scenario/workflow-example.json")) { fixture=ScenarioJson.MAPPER.readTree(input.readAllBytes()); }
        when(release.program()).thenReturn(registry.require("general-communication",1));
        when(release.promptHash()).thenReturn(new String(getClass().getResourceAsStream("/scenario/workflow-v1.sha256").readAllBytes()).trim());
        when(release.available()).thenReturn(true);
        when(release.available(anyLong())).thenReturn(true);
        when(release.personalizedPromptHash()).thenReturn(new String(getClass().getResourceAsStream("/scenario/workflow-v2.sha256").readAllBytes()).trim());
        when(release.assessmentPromptHash()).thenReturn(new String(getClass().getResourceAsStream("/scenario/workflow-v3.sha256").readAllBytes()).trim());
        when(release.require(anyLong(),anyString(),anyInt())).thenAnswer(call -> registry.require(call.getArgument(1),call.getArgument(2)));
        when(ai.local()).thenReturn(true); when(ai.scenarioProvider()).thenReturn("ollama");
        when(ai.audio(any())).thenCallRealMethod();
        var accounting=new AiUsageBudget(db,manager,true,10000000,1000000,10,20,100000,1000);
        doAnswer(call -> { accounting.reserveScenario(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4)); return null; })
                .when(budget).reserveScenario(anyLong(),anyString(),anyInt(),anyBoolean(),anyBoolean());
        when(ai.submitScenario(anyLong(),anyString(),any(),any())).thenAnswer(call -> done(call.getArgument(1),call.getArgument(3)));
    }
    ScenarioEngine.DraftRequest input() { return new ScenarioEngine.DraftRequest("Tôi muốn đặt bánh để nhận ngày mai","general-communication",1,"supported"); }
    JsonNode done(String id,JsonNode input) {
        String phase=input.path("phase").asText(); boolean multiVoice=input.path("kind").asText().equals("scenario_tts"),tts=multiVoice || input.path("kind").asText().equals("tts");
        boolean meaning=input.path("contractVersion").asText().equals("scenario-job.v3");
        boolean personalized=meaning || input.path("contractVersion").asText().equals("scenario-job.v2");
        boolean previousPersonalized=input.path("promptHash").asText().equals("bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa");
        boolean previousFewshot=input.path("promptHash").asText().equals("54f9fdeda01717f5c8853239357dad6dff2e0fa94df929f6c82e6d2497af52a1");
        boolean previousFewshotV2=input.path("promptHash").asText().equals("6a857865dddd79783a50c6dbf45bfdd281feeb56b681bdff34abd24c4df5b2e3");
        boolean previousFewshotV3=input.path("promptHash").asText().equals("f5f347bcac1dd0767f3af409704778357cd0ce39dd2a2d7f325671bc22abee1f");
        boolean previousFewshotV4=input.path("promptHash").asText().equals("aa79b767fee0970408e4fe420d1d97615595fdd195e16d8e919f28881f265485");
        boolean previousFewshotV5=input.path("promptHash").asText().equals("358201f08eb87c7d4d2306220c3828a92cda57ac93d57faed80a508d892fa1c1");
        boolean previousFewshotV6=input.path("promptHash").asText().equals("c1dcc2e3cc29847ba09b6d53a447a45e8bfe48ae13e9dfb4e2e131173f1e6a18");
        boolean harnessV9=input.path("promptHash").asText().equals("df832a8cb71a8229b95d02be4acaacd6a56b78ed7a9f747b51c4a222864cc303");
        boolean harness=harnessV9 || input.path("promptHash").asText().equals("a98960f21fabe1f652f68fac72794c41e5c18870a400059cef4b03f50a9a838b");
        var result=object().put("schemaVersion","1").put("generationId",id).put("requestId",id)
                .put("provider",tts ? "cartesia" : "ollama").put("model","fixture-model").put("modelDigest","fixture-digest")
                .put("promptVersion",tts ? "TTS_WAV_V1" : personalized ? (previousPersonalized ? "SCENARIO_PERSONALIZED_V1_GRADE_V4" : previousFewshot ? "SCENARIO_PERSONALIZED_FEWSHOT_V1_GRADE_V4" : previousFewshotV2 ? "SCENARIO_PERSONALIZED_FEWSHOT_V2_GRADE_V4" : previousFewshotV3 ? "SCENARIO_PERSONALIZED_FEWSHOT_V3_GRADE_V4" : previousFewshotV4 ? "SCENARIO_PERSONALIZED_FEWSHOT_V4_GRADE_V4" : previousFewshotV5 ? "SCENARIO_PERSONALIZED_FEWSHOT_V5_GRADE_V5" : previousFewshotV6 ? "SCENARIO_PERSONALIZED_FEWSHOT_V6_GRADE_V5" : harnessV9 ? "SCENARIO_PERSONALIZED_FEWSHOT_V9_HARNESS_V2_GRADE_V5" : harness ? "SCENARIO_PERSONALIZED_FEWSHOT_V8_HARNESS_V1_GRADE_V5" : "SCENARIO_PERSONALIZED_FEWSHOT_V7_GRADE_V5") : "SCENARIO_LISTENING_V5_GRADE_V4_1")
                .put("promptHash",input.path("promptHash").asText(release.promptHash())).put("programHash",input.path("programHash").asText());
        if (tts) {
            result.set("task",object().put("contentType","audio/wav").put("size",wave().length).put("audioBase64",Base64.getEncoder().encodeToString(wave())));
            if (multiVoice) result.put("promptVersion","TTS_WAV_V2").put("model",input.path("recipe").path("model").asText())
                    .put("speakerId",input.path("speakerId").asText()).put("voiceId",input.path("voiceId").asText())
                    .put("recipeHash",ScenarioJson.hash(ScenarioJson.canonical(input.path("recipe"))));
        }
        else {
            result.set("task",fixture.path(switch(phase) { case "EXTRACT" -> "specification"; case "GENERATE" -> "content"; default -> "grade"; }).deepCopy());
            if (personalized && phase.equals("EXTRACT")) ((ObjectNode)result.path("task")).put("learnerMode","listener").put("learnerRoleVi","Người nghe").put("genre","information-exchange");
            if (personalized && phase.equals("GENERATE")) for (var activity : result.path("task").path("activities")) for (var q : activity.path("questions"))
                ((ObjectNode)q).put("supportKind","direct").put("inferenceReasonEn","");
            if (personalized && phase.equals("GENERATE")) ((ObjectNode)result.path("task").path("activities").get(0).path("questions").get(0)).put("skill",input.path("learningContext").path("focusSkill").asText());
            var calls=result.putArray("calls");
        for (String purpose : phase.equals("GENERATE") ? List.of("GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW") : List.of(phase))
                calls.addObject().put("purpose",purpose).put("provider","ollama").put("model","fixture-model");
            if (harness && phase.equals("GENERATE")) result.putObject("qualityHarness").put("extraCalls",0)
                    .put("policyVersion",harnessV9 ? "scenario-quality-harness.v2" : "scenario-quality-harness.v1")
                    .put("policyHash",harnessV9 ? "e15c2b30a59f2b505b047b1920ebdff9c4cb7ccb2ae87cf9cf24803e2e8c2a66" : "ac1a80c90c9f24149f14a9221c0c7382e653d94fccbd823b3862c55fa043bc6b");
            if (meaning) {
                boolean parts=ScenarioAssessment.PARTS_POLICY.equals(ScenarioAssessment.policyFor(input.path("promptHash").asText()));
                boolean partsV4_1=ScenarioAssessment.PARTS_V4_1_PROMPT_HASH.equals(input.path("promptHash").asText());
                boolean partsV4_4=ScenarioAssessment.PARTS_V4_4_PROMPT_HASH.equals(input.path("promptHash").asText());
                boolean partsV4_3=ScenarioAssessment.PARTS_V4_3_PROMPT_HASH.equals(input.path("promptHash").asText());
                result.put("promptVersion",ScenarioAssessment.LEGACY_PROMPT_HASH.equals(input.path("promptHash").asText()) ? "SCENARIO_MEANING_ASSESSMENT_V1"
                        : ScenarioAssessment.WIRE_V2_PROMPT_HASH.equals(input.path("promptHash").asText()) ? "SCENARIO_MEANING_ASSESSMENT_V2" : partsV4_4 ? "SCENARIO_MEANING_ASSESSMENT_V4_4" : partsV4_3 ? "SCENARIO_MEANING_ASSESSMENT_V4_3" : partsV4_1 ? "SCENARIO_MEANING_ASSESSMENT_V4_1" : parts ? "SCENARIO_MEANING_ASSESSMENT_V4" : "SCENARIO_MEANING_ASSESSMENT_V3")
                        .put("assessmentPolicyVersion",ScenarioAssessment.policyFor(input.path("promptHash").asText()));
                if (ScenarioAssessment.V3_2_PROMPT_HASH.equals(input.path("promptHash").asText())) result.put("promptVersion","SCENARIO_MEANING_ASSESSMENT_V3_2");
                if (ScenarioAssessment.PARTS_V4_6_PROMPT_HASH.equals(input.path("promptHash").asText())) result.put("promptVersion","SCENARIO_MEANING_ASSESSMENT_V4_6");
                if (ScenarioAssessment.PARTS_V4_13_PROMPT_HASH.equals(input.path("promptHash").asText())) result.put("promptVersion","SCENARIO_MEANING_ASSESSMENT_V4_13");
                if (ScenarioAssessment.PARTS_V4_14_PROMPT_HASH.equals(input.path("promptHash").asText())) result.put("promptVersion","SCENARIO_MEANING_ASSESSMENT_V4_14");
                boolean independentAudit=ScenarioAssessment.independentAudit(input.path("promptHash").asText());
                if (independentAudit) result.put("promptVersion",ScenarioAssessment.PARTS_V4_11_PROMPT_HASH.equals(input.path("promptHash").asText())
                        ? "SCENARIO_MEANING_ASSESSMENT_V4_11" : ScenarioAssessment.PARTS_V4_12_PROMPT_HASH.equals(input.path("promptHash").asText())
                        ? "SCENARIO_MEANING_ASSESSMENT_V4_12" : "SCENARIO_MEANING_ASSESSMENT_V4_7");
                if (phase.equals("GENERATE")) {
                    result.set("task",(parts ? ScenarioAssessmentTest.partsFixture() : ScenarioAssessmentTest.fixture()).path("content"));
                    result.putObject("qualityHarness").put("extraCalls",0).put("policyVersion","scenario-meaning-harness.v1").put("policyHash","56d0d6d5e826e1b46f20b5ab4a3868dada9537cca996cb35999d3886ee3d6fe2");
                    if (independentAudit) {
                        calls.removeAll();
                        for (String purpose : List.of("GENERATE","QUESTIONS","AUDIT_MEANINGS","AUDIT_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW"))
                            calls.addObject().put("purpose",purpose).put("provider","ollama").put("model","fixture-model");
                        ((ObjectNode)result.path("qualityHarness")).put("policyVersion","scenario-meaning-harness.v2").put("policyHash",ScenarioAssessment.AUDIT_HARNESS_POLICY_HASH);
                    }
                } else if (phase.equals("GRADE")) {
                    var task=object(); var meanings=task.putArray("meanings"); var submission=input.path("assessmentSubmission");
                    for (var mid : submission.path("meaningIds")) {
                        var item=meanings.addObject().put("meaningId",mid.asText()).put("status",meaningGradeStatus); var evidence=item.putArray("evidence");
                        if (meaningGradeStatus.equals("CAPTURED") || parts && Set.of("PARTIAL","INCORRECT","UNCERTAIN").contains(meaningGradeStatus)) {
                            String response="recap",quote=submission.path("recapText").asText();
                            if (submission.path("mode").asText().equals("CHECKS")) for (var q : input.path("activity").path("questions")) if (java.util.stream.StreamSupport.stream(q.path("meaningIds").spliterator(),false).anyMatch(mid::equals))
                                for (var answer : submission.path("answers")) if (answer.path("questionId").equals(q.path("id"))) { response=answer.path("questionId").asText(); quote=answer.path("textVi").asText(); }
                            evidence.addObject().put("responseId",response).put("quote",quote);
                        }
                        if (parts) {
                            var part=item.putArray("parts").addObject().put("partId","p1")
                                    .put("status",Set.of("UNASSESSED","MISSED").contains(meaningGradeStatus) ? "ABSENT" : meaningGradeStatus);
                            part.set("evidence",evidence.deepCopy());
                        }
                    }
                    result.set("task",task);
                }
            }
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
    JsonNode activeMeaning() {
        long version=db.queryForObject("SELECT COALESCE(MAX(version),0) FROM scenario_learning_preferences WHERE user_id=? AND program_code='general-communication'",Long.class,user);
        personalization.save(user,id(),new ScenarioPersonalization.Preference("general-communication",4,new ScenarioGoalRequest.Target("cefr-listening","LISTENING","a1"),null,"detail",version));
        var op=dispatch(engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("A short listening lesson","general-communication",4,"a1","detail")));
        assertThat(op.path("status").asText()).isEqualTo("SUCCEEDED");
        var draft=engine.draft(user,op.path("draftId").asText());
        var generation=dispatch(engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong())));
        assertThat(generation.path("status").asText()).isEqualTo("SUCCEEDED");
        return engine.session(user,generation.path("sessionId").asText());
    }
    @ParameterizedTest @ValueSource(strings={ScenarioAssessment.PROMPT_HASH,ScenarioAssessment.V3_2_PROMPT_HASH})
    void meaningRecapSkipIsDurableFreeAndChecksAreServerSelectedAndSecretUntilDone(String pin) {
        when(release.assessmentPromptHash()).thenReturn(pin);
        var session=activeMeaning(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        assertThat(session.path("activities").get(0).path("questions")).isEmpty();
        int jobs=db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=?",Integer.class,user);
        String key=id(); var skip=new ScenarioEngine.AssessmentRequest(session.path("version").asLong(),"SKIP_RECAP","",List.of());
        session=engine.assessment(user,sid,aid,key,skip); assertThat(engine.assessment(user,sid,aid,key,skip).toString()).isEqualTo(session.toString());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=?",Integer.class,user)).isEqualTo(jobs);
        assertThat(session.path("activities").get(0).path("questions")).hasSize(3);
        var answers=new ArrayList<ScenarioEngine.Answer>(); for (var q : session.path("activities").get(0).path("questions")) answers.add(new ScenarioEngine.Answer(q.path("questionId").asText(),"I heard nine"));
        final long version=session.path("version").asLong();
        assertThatThrownBy(() -> engine.assessment(user,sid,aid,id(),new ScenarioEngine.AssessmentRequest(version,"CHECKS","",List.of(new ScenarioEngine.Answer("q6","forged"))))).isInstanceOf(ScenarioFailure.class);
        meaningGradeStatus="UNCERTAIN"; dispatch(engine.assessment(user,sid,aid,id(),new ScenarioEngine.AssessmentRequest(version,"CHECKS","",answers)));
        session=engine.session(user,sid); var activity=session.path("activities").get(0);
        assertThat(activity.path("assessment").path("stage").asText()).isEqualTo("DONE"); assertThat(activity.path("assessment").path("coverage").path("uncertain").asInt()).isEqualTo(3);
        assertThat(activity.path("questions").toString()).doesNotContain("answerVi","quoteEn");
        session=engine.completeActivity(user,sid,aid,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong()));
        assertThat(session.path("activities").get(0).path("questions")).hasSize(3); assertThat(session.path("activities").get(0).path("questions").get(0).has("answerVi")).isTrue();
    }
    @Test void fullIndependentRecapCompletesWithoutArtificialChecksAndPartialRecapDoesNotLeakResults() {
        var session=activeMeaning(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        dispatch(engine.assessment(user,sid,aid,id(),new ScenarioEngine.AssessmentRequest(session.path("version").asLong(),"RECAP","I heard nine, six pounds and tomorrow",List.of())));
        session=engine.session(user,sid); assertThat(session.path("activities").get(0).path("attempts").get(0).has("result")).isFalse();
        assertThat(session.path("activities").get(0).path("assessment").path("coverage").has("meanings")).isFalse();
        // A different activity with a full recap has no forced check batch.
        var another=activeMeaning(); String other=another.path("sessionId").asText(),a=another.path("activities").get(0).path("activityId").asText();
        meaningGradeStatus="CAPTURED"; dispatch(engine.assessment(user,other,a,id(),new ScenarioEngine.AssessmentRequest(another.path("version").asLong(),"RECAP","nine, six pounds, tomorrow",List.of())));
        another=engine.session(user,other); assertThat(another.path("activities").get(0).path("assessment").path("canComplete").asBoolean()).isTrue(); assertThat(another.path("activities").get(0).path("questions")).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={ScenarioAssessment.PARTS_PROMPT_HASH,ScenarioAssessment.PARTS_V4_1_PROMPT_HASH,ScenarioAssessment.PARTS_V4_3_PROMPT_HASH,ScenarioAssessment.PARTS_V4_4_PROMPT_HASH,ScenarioAssessment.PARTS_V4_6_PROMPT_HASH,ScenarioAssessment.PARTS_V4_7_PROMPT_HASH,ScenarioAssessment.PARTS_V4_11_PROMPT_HASH,ScenarioAssessment.PARTS_V4_12_PROMPT_HASH,ScenarioAssessment.PARTS_V4_13_PROMPT_HASH,ScenarioAssessment.PARTS_V4_14_PROMPT_HASH})
    void requiredPartLessonKeepsItsPolicyAndPinThroughChecksCompletionAndAdmissionChanges(String pin) {
        when(release.assessmentPromptHash()).thenReturn(pin);
        var session=activeMeaning(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        assertThat(db.queryForObject("SELECT b.calls_reserved FROM scenario_operation_budget b JOIN scenario_operations o ON o.id=b.operation_id WHERE o.kind='GENERATE' AND o.resource_id=?",Integer.class,sid))
                .isEqualTo(ScenarioAssessment.independentAudit(pin) ? 11 : 9);
        assertThat(session.path("activities").get(0).path("assessment").path("policyVersion").asText()).isEqualTo(ScenarioAssessment.PARTS_POLICY);
        session=engine.assessment(user,sid,aid,id(),new ScenarioEngine.AssessmentRequest(session.path("version").asLong(),"SKIP_RECAP","",List.of()));
        assertThat(session.toString()).doesNotContain("requiredParts","answerVi","quoteEn");
        var answers=new ArrayList<ScenarioEngine.Answer>(); for (var q : session.path("activities").get(0).path("questions")) answers.add(new ScenarioEngine.Answer(q.path("questionId").asText(),"I recall part of this."));
        when(release.assessmentPromptHash()).thenReturn(ScenarioAssessment.PROMPT_HASH);
        meaningGradeStatus="PARTIAL";
        var operation=engine.assessment(user,sid,aid,id(),new ScenarioEngine.AssessmentRequest(session.path("version").asLong(),"CHECKS","",answers));
        var pinned=tree(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,operation.path("operationId").asText()));
        assertThat(pinned.path("promptHash").asText()).isEqualTo(pin);
        assertThat(dispatch(operation).path("status").asText()).isEqualTo("SUCCEEDED");
        session=engine.session(user,sid); var assessment=session.path("activities").get(0).path("assessment");
        assertThat(assessment.path("coverage").path("partial").asInt()).isEqualTo(3);
        assertThat(assessment.path("canComplete").asBoolean()).isTrue();
        session=engine.completeActivity(user,sid,aid,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong()));
        assertThat(session.path("activities").get(0).path("questions").get(0).has("answerVi")).isTrue();
    }
    @Test void historicalGradeRecoversGenerationPinAndRejectsMissingProvenanceAndEmergencyStop() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        String draft=db.queryForObject("SELECT draft_id FROM scenario_sessions WHERE id=?",String.class,sid),pin=release.promptHash();
        db.update("UPDATE scenario_drafts SET workflow_snapshot_json=NULL WHERE id=?",draft);
        var extraction=db.queryForMap("SELECT id,payload_json FROM scenario_operations WHERE kind='EXTRACT' AND resource_id=?",draft);
        var input=(ObjectNode)tree((String)extraction.get("payload_json")); input.put("promptHash","0d40fae31e57eef602c6f1e8402ea6116f8beeee56ac011c7c3d018f7f0152c2"); db.update("UPDATE scenario_operations SET payload_json=? WHERE id=?",input.toString(),extraction.get("id"));
        when(release.promptHash()).thenReturn("0".repeat(64));
        var grade=engine.answer(user,sid,aid,id(),answers(session));
        assertThat(tree(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,grade.path("operationId").asText())).path("promptHash").asText()).isEqualTo(pin);
        when(release.promptHash()).thenReturn(pin);
        var other=active(); String otherSid=other.path("sessionId").asText(),otherAid=other.path("activities").get(0).path("activityId").asText();
        String otherDraft=db.queryForObject("SELECT draft_id FROM scenario_sessions WHERE id=?",String.class,otherSid);
        db.update("UPDATE scenario_drafts SET workflow_snapshot_json=NULL WHERE id=?",otherDraft);
        db.update("DELETE FROM scenario_operations WHERE user_id=? AND resource_id IN (?,?)",user,otherDraft,otherSid);
        assertThatThrownBy(() -> engine.answer(user,otherSid,otherAid,id(),answers(other))).isInstanceOfSatisfying(ScenarioFailure.class,f -> assertThat(f.code).isEqualTo("WORKFLOW_PROVENANCE_MISSING"));
        var stopped=active(); String stoppedSid=stopped.path("sessionId").asText(),stoppedAid=stopped.path("activities").get(0).path("activityId").asText();
        db.update("UPDATE ai_budget_control SET enabled=FALSE WHERE id=1");
        try { assertThatThrownBy(() -> engine.answer(user,stoppedSid,stoppedAid,id(),answers(stopped))).isInstanceOf(AiUsageBudget.Rejected.class); }
        finally { db.update("UPDATE ai_budget_control SET enabled=TRUE WHERE id=1"); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_attempts WHERE activity_id=?",Integer.class,stoppedAid)).isZero();
        doThrow(new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","Disabled")).when(release).requireGrade(anyLong(),anyString(),anyInt(),anyString());
        assertThatThrownBy(() -> engine.answer(user,stoppedSid,stoppedAid,id(),answers(stopped))).isInstanceOf(ScenarioFailure.class);
    }
    ScenarioEngine.AnswersRequest answers(JsonNode session) {
        return new ScenarioEngine.AnswersRequest(session.path("version").asLong(),List.of(
                new ScenarioEngine.Answer("q1",fixture.path("answers").get(0).path("textVi").asText()),
                new ScenarioEngine.Answer("q2",fixture.path("answers").get(1).path("textVi").asText())));
    }

    ScenarioPersonalization.Preference preference(String code,String target,String current,long version) {
        var program=registry.require(code,2);
        var scale=program.targetScales().stream().filter(s -> s.component().equals("LISTENING")).findFirst().orElseThrow();
        return new ScenarioPersonalization.Preference(code,2,new ScenarioGoalRequest.Target(scale.scaleId(),"LISTENING",target),current,"detail",version);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa", "54f9fdeda01717f5c8853239357dad6dff2e0fa94df929f6c82e6d2497af52a1", "f5f347bcac1dd0767f3af409704778357cd0ce39dd2a2d7f325671bc22abee1f", "aa79b767fee0970408e4fe420d1d97615595fdd195e16d8e919f28881f265485", "358201f08eb87c7d4d2306220c3828a92cda57ac93d57faed80a508d892fa1c1", "c1dcc2e3cc29847ba09b6d53a447a45e8bfe48ae13e9dfb4e2e131173f1e6a18", "defd36c45a53aa6ac5002d0acb50996a5790fd87554646e36f9830dd3a225504"})
    void queuedPersonalizedReleaseSettlesWithoutBeingRelabelled(String baseline) {
        String current=release.personalizedPromptHash();
        personalization.save(user,id(),preference("toeic-listening","250","",0));
        when(release.personalizedPromptHash()).thenReturn(baseline);
        var pending=engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("","toeic-listening",2,""));
        String operationId=pending.path("operationId").asText();
        assertThat(tree(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,operationId))
                .path("promptHash").asText()).isEqualTo(baseline);
        when(release.personalizedPromptHash()).thenReturn(current);
        assertThat(dispatch(pending).path("status").asText()).isEqualTo("SUCCEEDED");
        var draft=engine.draft(user,pending.path("draftId").asText());
        var generation=engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong()));
        String nextId=generation.path("operationId").asText();
        assertThat(tree(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,nextId))
                .path("promptHash").asText()).isEqualTo(baseline);
        assertThat(dispatch(generation).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"old_version","old_fewshot_version","wrong_hash","relabel_baseline","relabel_fewshot"})
    void personalizedResultCannotSwapPromptHashOrVersion(String fault) {
        personalization.save(user,id(),preference("toeic-listening","250","",0));
        if (fault.equals("relabel_baseline")) when(release.personalizedPromptHash())
                .thenReturn("bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa");
        if (fault.equals("relabel_fewshot")) when(release.personalizedPromptHash())
                .thenReturn("54f9fdeda01717f5c8853239357dad6dff2e0fa94df929f6c82e6d2497af52a1");
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            var result=(ObjectNode)response.path("result");
            if (fault.equals("wrong_hash")) result.put("promptHash","0".repeat(64));
            else result.put("promptVersion",fault.equals("old_version") ? "SCENARIO_PERSONALIZED_V1_GRADE_V4" : fault.equals("old_fewshot_version") ? "SCENARIO_PERSONALIZED_FEWSHOT_V1_GRADE_V4" : "SCENARIO_PERSONALIZED_FEWSHOT_V3_GRADE_V4");
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var pending=engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("","toeic-listening",2,""));
        assertThat(dispatch(pending).path("status").asText()).isEqualTo("FAILED");
    }

    @Test void personalizedDraftSessionAndRemixKeepOriginalSnapshotAfterPreferenceChanges() {
        String key=id(); var request=preference("toeic-listening","250","200",0);
        var saved=personalization.save(user,key,request);
        assertThat(personalization.save(user,key,request).toString()).isEqualTo(saved.toString());
        var extraction=dispatch(engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("","toeic-listening",2,"")));
        assertThat(extraction.path("status").asText()).isEqualTo("SUCCEEDED");
        String draftId=extraction.path("draftId").asText(); var draft=engine.draft(user,draftId);
        var snapshot=draft.path("learningContext");
        assertThat(snapshot.path("target").path("value").asText()).isEqualTo("250");
        assertThat(snapshot.path("currentValue").isNull()).isTrue();
        assertThat(snapshot.path("focusSkill").asText()).isEqualTo("main-idea");
        assertThat(snapshot.path("profile").path("id").asText()).isEqualTo("supported");
        personalization.save(user,id(),preference("toeic-listening","495","",1));
        assertThat(engine.draft(user,draftId).path("learningContext")).isEqualTo(snapshot);
        var generation=dispatch(engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draftId,draft.path("version").asLong())));
        assertThat(generation.path("status").asText()).isEqualTo("SUCCEEDED");
        String sid=generation.path("sessionId").asText(); JsonNode session=engine.session(user,sid);
        for (int ordinal=0; ordinal<3; ordinal++) {
            String aid=session.path("activities").get(ordinal).path("activityId").asText();
            assertThat(dispatch(engine.answer(user,sid,aid,id(),answers(session))).path("status").asText()).isEqualTo("SUCCEEDED");
            session=engine.session(user,sid);
            session=engine.completeActivity(user,sid,aid,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong()));
        }
        assertThat(session.path("learningContext")).isEqualTo(snapshot);
        var remix=dispatch(engine.remix(user,sid,id(),new ScenarioEngine.RemixRequest(session.path("version").asLong(),"Đổi bối cảnh thành thư viện","supported")));
        assertThat(engine.draft(user,remix.path("draftId").asText()).path("learningContext")).isEqualTo(snapshot);
        verify(ai,atLeastOnce()).submitScenario(eq(user),anyString(),any(),argThat(p -> "EXTRACT".equals(p.path("phase").asText())
                && !p.path("inputVi").asText().isBlank() && snapshot.equals(p.path("learningContext"))));
        verify(ai,atLeastOnce()).submitScenario(eq(user),anyString(),any(),argThat(p -> "GRADE".equals(p.path("phase").asText()) && snapshot.equals(p.path("learningContext"))));
    }

    @Test void preferencesRequireCsrfAreOwnerScopedAndEnforceVersionsAndAdmission() throws Exception {
        var request=preference("ielts-listening","6.5","",0);
        mvc.perform(post("/api/v2/scenarios/learning-preferences").with(user(DictaLabPrincipal.from(owner)))
                .header("Idempotency-Key",id()).contentType("application/json").content(json(request))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/scenarios/learning-preferences").with(user(DictaLabPrincipal.from(owner))).with(csrf())
                .header("Idempotency-Key",id()).contentType("application/json").content(json(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.learningContext.profile.id").value("standard"));
        assertThatThrownBy(() -> personalization.save(user,id(),request)).isInstanceOfSatisfying(ScenarioFailure.class,e -> assertThat(e.code).isEqualTo("PREFERENCE_VERSION_CONFLICT"));
        var other=users.saveAndFlush(new AppUser(id()+"@personal.test","Other","unused",Instant.now()));
        mvc.perform(get("/api/v2/scenarios/learning-preferences").with(user(DictaLabPrincipal.from(other))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preferences.length()").value(0));
        assertThatThrownBy(() -> engine.createDraft(other.getId(),id(),new ScenarioEngine.DraftRequest("","ielts-listening",2,"")))
                .isInstanceOfSatisfying(ScenarioFailure.class,e -> assertThat(e.code).isEqualTo("PERSONALIZATION_REQUIRED"));
    }

    @Test void newDraftUsesVisibleTargetInsteadOfLegacyHiddenPreferencesAndSupportsCefr() {
        personalization.save(user,id(),preference("toeic-listening","450","200",0));
        var context=personalization.forDraft(user,registry.require("toeic-listening",2));
        assertThat(context.path("profile").path("id").asText()).isEqualTo("challenge");
        assertThat(context.path("currentValue").isNull()).isTrue();
        assertThat(context.path("focusSkill").asText()).isEqualTo("main-idea");
        var request=new ScenarioPersonalization.Preference("general-communication",3,new ScenarioGoalRequest.Target("cefr-listening","LISTENING","b2"),"","main-idea",0);
        personalization.save(user,id(),request);
        var operation=dispatch(engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("","general-communication",3,"")));
        assertThat(operation.path("status").asText()).isEqualTo("SUCCEEDED");
        var snapshot=engine.draft(user,operation.path("draftId").asText()).path("learningContext");
        assertThat(snapshot.path("target").path("value").asText()).isEqualTo("b2");
        assertThat(snapshot.path("profile").path("id").asText()).isEqualTo("b2");
        assertThat(snapshot.path("profile").path("vocabularyGuidance").asText()).contains("CEFR B2");
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

    @Test void providerOverloadKeepsUnknownOutcomeAndDistinctCodeWithoutResubmitting() {
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1))
                .put("status","UNKNOWN").put("error","PROVIDER_UNAVAILABLE"))
                .when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var op=engine.createDraft(user,id(),input()); String operationId=op.path("operationId").asText();
        operations.dispatch(operationId);
        var result=operations.get(user,operationId);
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("errorCode").asText()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        operations.dispatch(operationId);
        verify(ai,times(1)).submitScenario(anyLong(),eq(operationId),any(),any());
        assertThat(engine.draft(user,op.path("draftId").asText()).path("status").asText()).isEqualTo("FAILED");
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
        var failedSession=engine.session(user,generation.path("sessionId").asText());
        assertThat(failedSession.path("status").asText()).isEqualTo("PREPARATION_FAILED");
        assertThat(failedSession.path("inputVi").asText()).isEqualTo(input().inputVi());
        assertThat(failedSession.path("preparationError").path("operationId").asText()).isEqualTo(generation.path("operationId").asText());
        assertThat(draft.path("inputVi").asText()).isEqualTo(input().inputVi());
        doAnswer(call -> done(call.getArgument(1),call.getArgument(3))).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","UNKNOWN")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var audio=dispatch(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1")));
        assertThat(audio.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(engine.session(user,sid).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test void personalizedAdvisoryReviewDoesNotBlockPublication() {
        personalization.save(user,id(),preference("toeic-listening","250","",0));
        var extraction=dispatch(engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("","toeic-listening",2,"")));
        var draft=engine.draft(user,extraction.path("draftId").asText());
        doAnswer(call -> {
            var response=(ObjectNode)done(call.getArgument(1),call.getArgument(3));
            ((ObjectNode)response.path("result").path("task")).put("qualityPassed",false);
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var operation=dispatch(engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong())));
        assertThat(operation.path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"wrong_provider","wrong_model","wrong_hash","wrong_extra","wrong_order"})
    void personalizedHarnessRejectsForgedProvenance(String fault) {
        personalization.save(user,id(),preference("toeic-listening","250","",0));
        var extraction=dispatch(engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("","toeic-listening",2,"")));
        var draft=engine.draft(user,extraction.path("draftId").asText());
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            var result=(ObjectNode)response.path("result");
            var first=(ObjectNode)result.path("calls").get(0);
            switch (fault) {
                case "wrong_provider" -> first.put("provider","gemini");
                case "wrong_model" -> first.put("model","");
                case "wrong_hash" -> result.put("promptHash","0".repeat(64));
                case "wrong_extra" -> ((ObjectNode)result.path("qualityHarness")).put("extraCalls",1);
                case "wrong_order" -> first.put("purpose","QUESTIONS");
            }
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var op=engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong()));
        assertThat(dispatch(op).path("status").asText()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_activities WHERE session_id=?",Integer.class,op.path("sessionId").asText())).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"valid","undeclared","duplicate","wrong_primary","wrong_provider","foreign_model","extra_field"})
    void geminiFallbackRequiresExplicitConsistentModelProvenance(String fault) {
        var extraction=dispatch(engine.createDraft(user,id(),input()));
        var draft=engine.draft(user,extraction.path("draftId").asText());
        when(ai.scenarioProvider()).thenReturn("gemini");
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            var result=(ObjectNode)response.path("result");
            result.put("provider","gemini").put("model","gemini-3.5-flash-lite");
            int index=0;
            for (var entry : result.path("calls")) ((ObjectNode)entry).put("provider","gemini")
                    .put("model",index++==0 ? "gemini-3.5-flash-lite" : "gemini-3.1-flash-lite");
            var routing=result.putObject("modelRouting").put("primaryModel","gemini-3.5-flash-lite");
            var models=routing.putArray("fallbackModels").add("gemini-3.1-flash-lite");
            switch (fault) {
                case "undeclared" -> result.remove("modelRouting");
                case "duplicate" -> models.add("gemini-3.1-flash-lite");
                case "wrong_primary" -> routing.put("primaryModel","gemini-2.5-flash");
                case "wrong_provider" -> result.put("provider","deepseek");
                case "foreign_model" -> models.add("deepseek-chat");
                case "extra_field" -> routing.put("unchecked",true);
            }
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var op=engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong()));
        assertThat(dispatch(op).path("status").asText()).isEqualTo(fault.equals("valid") ? "SUCCEEDED" : "FAILED");
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

    @Test void correctionAndHintPointToListeningSkillsWithoutExposingAnswersOrChangingSavedGrades() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        var hint=engine.help(user,sid,aid,id(),new ScenarioEngine.HelpRequest(session.path("version").asLong(),"HINT"));
        assertThat(hint.path("hintVi").asText()).contains("thông tin cụ thể").doesNotContain("Chín giờ","we open at nine");
        session=engine.session(user,sid);
        assertThat(session.path("activities").get(0).path("hintOpened").asBoolean()).isTrue();
        assertThat(session.path("activities").get(0).path("transcriptOpened").asBoolean()).isFalse();
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            ((ObjectNode)response.path("result").path("task").path("facts").get(0)).put("status","PARTIAL");
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        assertThat(dispatch(engine.answer(user,sid,aid,id(),answers(session))).path("status").asText()).isEqualTo("SUCCEEDED");
        var visible=engine.session(user,sid).path("activities").get(0);
        String cue=visible.path("attempts").get(0).path("result").path("facts").get(0).path("feedbackVi").asText();
        assertThat(cue).contains("Hãy nghe lại đoạn 2","thông tin cụ thể").doesNotContain("Chín giờ","we open at nine");
        assertThat(visible.toString()).doesNotContain("answerVi","quoteEn","textEn");
        assertThat(db.queryForObject("SELECT result_json FROM scenario_attempts WHERE activity_id=?",String.class,aid)).doesNotContain("feedbackVi");
        assertThat(engine.recommendations(user).path("observedFacts").asInt()).isZero();
    }

    @Test void explicitRecommendationSkillReachesTheDraftAndUnsupportedSkillsStayRejected() {
        personalization.save(user,id(),preference("ielts-listening","6.5","",0));
        var op=dispatch(engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("Cuộc trao đổi ở công ty","ielts-listening",2,"","reason")));
        var context=engine.draft(user,op.path("draftId").asText()).path("learningContext");
        assertThat(context.path("focusSkill").asText()).isEqualTo("reason");
        assertThat(context.path("target").path("value").asText()).isEqualTo("6.5");
        assertThat(personalization.forDraft(user,registry.require("ielts-listening",2)).path("focusSkill").asText()).isEqualTo("main-idea");
        var program=registry.require("general-communication",3);
        personalization.save(user,id(),new ScenarioPersonalization.Preference(program.code(),program.version(),new ScenarioGoalRequest.Target("cefr-listening","LISTENING","a1"),"","main-idea",0));
        assertThatThrownBy(() -> engine.createDraft(user,id(),new ScenarioEngine.DraftRequest("Cuộc gặp","general-communication",3,"","supported-inference"))).isInstanceOf(ScenarioFailure.class);
    }

    @Test void optionalDraftFocusPreservesLegacyFingerprintsAndStrictHttpValidation() throws Exception {
        assertThat(json(input())).doesNotContain("focusSkill");
        mvc.perform(post("/api/v2/scenarios/drafts").with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                .contentType("application/json").content(json(input()))).andExpect(status().isOk());
        mvc.perform(post("/api/v2/scenarios/drafts").with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                .contentType("application/json").content("{\"programCode\":\"general-communication\",\"programVersion\":1,\"profileId\":\"supported\"}"))
                .andExpect(status().isBadRequest());
        personalization.save(user,id(),preference("ielts-listening","6.5","",0));
        mvc.perform(post("/api/v2/scenarios/drafts").with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                .contentType("application/json").content(json(new ScenarioEngine.DraftRequest("Cuộc họp","ielts-listening",2,"","reason"))))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"reason,hiểu lý do","purpose,hiểu mục đích","speaker-attitude,hiểu quan điểm","supported-inference,suy luận có căn cứ"})
    void recommendationsRetainTheActualWeakSkill(String skill,String label) {
        var session=active(); String sid=session.path("sessionId").asText();
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            for(var fact : response.path("result").path("task").path("facts")) ((ObjectNode)fact).put("status","PARTIAL");
            return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        for(int i=0;i<3;i++) {
            session=engine.session(user,sid); String aid=session.path("activities").get(i).path("activityId").asText();
            var content=tree(db.queryForObject("SELECT private_json FROM scenario_activities WHERE id=?",String.class,aid));
            for(var q : content.path("activity").path("questions")) ((ObjectNode)q).put("skill",skill);
            db.update("UPDATE scenario_activities SET private_json=? WHERE id=?",json(content),aid);
            assertThat(dispatch(engine.answer(user,sid,aid,id(),answers(session))).path("status").asText()).isEqualTo("SUCCEEDED");
            session=engine.session(user,sid); engine.completeActivity(user,sid,aid,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong()));
        }
        var recommendations=engine.recommendations(user);
        assertThat(recommendations.path("basis").asText()).isEqualTo("RECENT_UNAIDED_FIRST_ATTEMPTS");
        for(var suggestion : recommendations.path("suggestions")) {
            assertThat(suggestion.path("focusSkill").asText()).isEqualTo(skill);
            assertThat(suggestion.path("reasonVi").asText()).contains(label);
            assertThat(suggestion.path("inputVi").asText()).contains(label);
        }
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

    @Test void unrestrictedGenerationPreservesEnvelopeAndIdempotencyPastDailyAndPendingCaps() {
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("dictalab.operational-limits-enabled","false");
        var provider=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        provider.registerSingleton("engine",engine);
        var unlimitedOperations=new ScenarioOperations(data,ai,budget,provider.getBeanProvider(ScenarioEngine.class),environment);
        var unlimitedEngine=new ScenarioEngine(data,unlimitedOperations,release,ai,media,null,environment,personalization,engineConfig);
        var extract=dispatch(unlimitedEngine.createDraft(user,id(),input()));
        var draft=unlimitedEngine.draft(user,extract.path("draftId").asText());
        for(int i=0;i<20;i++) db.update("INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json) VALUES (?,?,'cafe','A1','{}')",id(),user);
        for(int i=0;i<4;i++) unlimitedEngine.createDraft(user,id(),input());
        String key=id(); var request=new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong());
        var op=unlimitedEngine.createSession(user,key,request);
        assertThat(unlimitedEngine.createSession(user,key,request)).isEqualTo(op);
        assertThat(db.queryForObject("SELECT calls_reserved FROM scenario_operation_budget WHERE operation_id=?",Integer.class,op.path("operationId").asText())).isEqualTo(5);
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
    @org.junit.jupiter.params.provider.ValueSource(strings={"skip_questions","wrong_order","old_version","old_v3_version","old_v4_version","old_v5_version","wrong_hash"})
    void currentWorkflowRejectsSkippedStagesAndVersionDowngrade(String fault) {
        var extract=dispatch(engine.createDraft(user,id(),input())); var draft=engine.draft(user,extract.path("draftId").asText());
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            var result=(ObjectNode)response.path("result");
            var calls=(tools.jackson.databind.node.ArrayNode)result.path("calls");
            if (fault.equals("skip_questions")) calls.remove(1);
            else if (fault.equals("wrong_order")) ((ObjectNode)calls.get(1)).put("purpose","TRANSLATE");
            else if (fault.equals("old_v3_version")) result.put("promptVersion","SCENARIO_LISTENING_V3");
            else if (fault.equals("old_v4_version")) result.put("promptVersion","SCENARIO_LISTENING_V4");
            else if (fault.equals("old_v5_version")) result.put("promptVersion","SCENARIO_LISTENING_V5_CANDIDATE_1");
            else if (fault.equals("wrong_hash")) result.put("promptHash","0".repeat(64));
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
            "3391407dddc9762afe3774cb30ee60e4a9f0936e70597e8a057b8bdfe03395fe",
            "c8e817015d97850288b8bbe377934602cc1f4df1a4b7ce18a2d5b5de8b28c2b5",
            "e3b045db46013c6e9694ae450f8beeb168bc1b452cb1548bcbe43a4eb247535b",
            "6a3a61aa0762dce1e0898bec5f1cf46b5ae6189dddbe3015828a35667ebe6f8f"})
    void alreadySubmittedJobCanSettleUnderItsOriginalPin(String oldPin) {
        var extract=dispatch(engine.createDraft(user,id(),input())); var draft=engine.draft(user,extract.path("draftId").asText());
        var op=engine.createSession(user,id(),new ScenarioEngine.SessionRequest(draft.path("draftId").asText(),draft.path("version").asLong()));
        String oid=op.path("operationId").asText();
        var original=(ObjectNode)tree(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,oid));
        original.put("promptHash",oldPin);
        db.update("UPDATE scenario_operations SET payload_json=?,submitted=TRUE WHERE id=?",json(original),oid);
        var response=done(oid,original); var result=(ObjectNode)response.path("result");
        boolean previousV3=Set.of("3391407dddc9762afe3774cb30ee60e4a9f0936e70597e8a057b8bdfe03395fe",
                "c8e817015d97850288b8bbe377934602cc1f4df1a4b7ce18a2d5b5de8b28c2b5").contains(oldPin);
        boolean previousV4=oldPin.equals("e3b045db46013c6e9694ae450f8beeb168bc1b452cb1548bcbe43a4eb247535b");
        boolean previousV5=oldPin.equals("6a3a61aa0762dce1e0898bec5f1cf46b5ae6189dddbe3015828a35667ebe6f8f");
        result.put("promptHash",oldPin).put("promptVersion",previousV3 ? "SCENARIO_LISTENING_V3" : previousV4 ? "SCENARIO_LISTENING_V4" : previousV5 ? "SCENARIO_LISTENING_V5_CANDIDATE_1" : "SCENARIO_LISTENING_V1");
        var calls=result.putArray("calls");
        for (String purpose : previousV3 ? List.of("GENERATE","FACTS","QUESTIONS","TRANSLATE","REVIEW")
                : previousV4 || previousV5 ? List.of("GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW") : List.of("GENERATE","TRANSLATE","REVIEW"))
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

    @Test void gradeRetryPreservesSubmissionAndHistoricalPayloadWithoutSpendingAnotherLearnerAttempt() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","FAILED")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var failed=dispatch(engine.answer(user,sid,aid,id(),answers(session))); String attempt=failed.path("attemptId").asText();
        String original=db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,failed.path("operationId").asText());
        String support=db.queryForObject("SELECT support_json FROM scenario_attempts WHERE id=?",String.class,attempt);
        session=engine.session(user,sid);
        assertThat(session.path("activities").get(0).path("attempts").get(0).path("gradeRetry").path("allowed").asBoolean()).isTrue();
        var request=new ScenarioEngine.VersionRequest(session.path("version").asLong()); String key=id();
        var retry=engine.retryGrade(user,sid,aid,attempt,key,request);
        assertThat(engine.retryGrade(user,sid,aid,attempt,key,request)).isEqualTo(retry);
        assertThat(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,retry.path("operationId").asText())).isEqualTo(original);
        assertThatThrownBy(() -> engine.retryGrade(user,sid,aid,attempt,id(),request)).isInstanceOf(ScenarioFailure.class);
        doAnswer(call -> done(call.getArgument(1),call.getArgument(3))).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        assertThat(dispatch(retry).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_attempts WHERE activity_id=?",Integer.class,aid)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT support_json FROM scenario_attempts WHERE id=?",String.class,attempt)).isEqualTo(support);
        session=engine.session(user,sid);
        engine.completeActivity(user,sid,aid,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong()));
    }

    @Test void gradeRetryIsBoundedAndUnknownCannotCreateAnotherPaidOperation() throws Exception {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","FAILED")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var failed=dispatch(engine.answer(user,sid,aid,id(),answers(session))); String attempt=failed.path("attemptId").asText();
        for (int i=0;i<2;i++) {
            session=engine.session(user,sid);
            dispatch(engine.retryGrade(user,sid,aid,attempt,id(),new ScenarioEngine.VersionRequest(session.path("version").asLong())));
        }
        var version=new ScenarioEngine.VersionRequest(engine.session(user,sid).path("version").asLong());
        assertThatThrownBy(() -> engine.retryGrade(user,sid,aid,attempt,id(),version)).isInstanceOf(ScenarioFailure.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE kind='GRADE' AND resource_id=?",Integer.class,attempt)).isEqualTo(3);
        db.update("UPDATE scenario_attempts SET status='UNKNOWN' WHERE id=?",attempt);
        assertThatThrownBy(() -> engine.retryGrade(user,sid,aid,attempt,id(),version)).isInstanceOf(ScenarioFailure.class);
        var other=users.saveAndFlush(new AppUser(id()+"@retry.test","Other","unused",Instant.now()));
        assertThatThrownBy(() -> engine.retryGrade(other.getId(),sid,aid,attempt,id(),version)).isInstanceOf(ScenarioFailure.class);
        mvc.perform(post("/api/v2/scenarios/sessions/"+sid+"/activities/"+aid+"/attempts/"+attempt+"/retry-grade")
                .with(user(DictaLabPrincipal.from(owner))).header("Idempotency-Key",id()).contentType("application/json")
                .content("{\"expectedVersion\":"+version.expectedVersion()+"}")).andExpect(status().isForbidden());
    }

    JsonNode recipe() {
        return tree("{\"id\":\"qa-voices-v1\",\"model\":\"fixture-model\",\"apiVersion\":\"2026-08-14\",\"language\":\"en\",\"outputFormat\":\"wav-pcm16-mono-24000\",\"voices\":[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000002\"]}");
    }

    @Test void voiceSnapshotSurvivesGenerationAndConfigurationChangeAndCachesEachSpeaker() {
        var recipe=recipe(); doReturn(recipe).when(engineConfig).ttsRecipe();
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doReturn(null).when(engineConfig).ttsRecipe();
        for (int i=0;i<2;i++) {
            session=engine.session(user,sid);
            var result=dispatch(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s"+(i+1))));
            assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
            var payload=tree(db.queryForObject("SELECT payload_json FROM scenario_operations WHERE id=?",String.class,result.path("operationId").asText()));
            assertThat(payload.path("kind").asText()).isEqualTo("scenario_tts");
            assertThat(payload.path("voiceId").asText()).isEqualTo(recipe.path("voices").get(i).asText());
            assertThat(payload.path("recipe")).isEqualTo(recipe);
        }
        session=engine.session(user,sid);
        assertThat(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1")).path("status").asText()).isEqualTo("READY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE kind='TTS' AND user_id=?",Integer.class,user)).isEqualTo(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"voiceId","speakerId","recipeHash","model"})
    void wrongVoiceMetadataCannotPublishAudio(String field) {
        doReturn(recipe()).when(engineConfig).ttsRecipe();
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doAnswer(call -> {
            var response=done(call.getArgument(1),call.getArgument(3));
            ((ObjectNode)response.path("result")).put(field,"wrong"); return response;
        }).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        assertThat(dispatch(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1"))).path("status").asText()).isEqualTo("FAILED");
    }

    @Test void openedHintSurvivesSessionReloadWithoutLeakingAnswers() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        var help=engine.help(user,sid,aid,id(),new ScenarioEngine.HelpRequest(session.path("version").asLong(),"HINT"));
        var activity=engine.session(user,sid).path("activities").get(0);
        assertThat(activity.path("hintVi")).isEqualTo(help.path("hintVi"));
        assertThat(activity.path("questions").get(0).has("answerVi")).isFalse();
        assertThat(activity.path("segments").get(0).has("textEn")).isFalse();
    }

    @Test void restrictedHistoricalProgramOnlyOffersAudioFromItsExactReadyCache() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        dispatch(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1")));
        doThrow(new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","Chương trình chưa mở.")).when(release).requireAudio(eq(user),anyString(),anyInt(),anyString());
        var saved=engine.session(user,sid);
        assertThat(saved.path("playbackRestrictionVi").asText()).contains("chưa mở");
        assertThat(saved.path("activities").get(0).path("segments").get(0).path("canPlay").asBoolean()).isTrue();
        assertThat(saved.path("activities").get(0).path("segments").get(1).path("canPlay").asBoolean()).isFalse();
        assertThat(engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(saved.path("version").asLong(),"s1")).path("status").asText()).isEqualTo("READY");
        var version=engine.session(user,sid).path("version").asLong();
        assertThatThrownBy(()->engine.play(user,sid,aid,id(),new ScenarioEngine.PlayRequest(version,"s2"))).isInstanceOf(ScenarioFailure.class);
    }

    @Test void changingGenerationApprovalDoesNotDisableSavedLessonAudioPreparation() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        doThrow(new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","New generation disabled")).when(release).require(user);
        var saved=engine.session(user,sid);
        assertThat(saved.has("playbackRestrictionVi")).isFalse();
        assertThat(saved.path("activities").get(0).path("segments").get(1).path("canPlay").asBoolean(true)).isTrue();
        var op=engine.prepareAudio(user,sid,aid,id(),new ScenarioEngine.PlayRequest(saved.path("version").asLong(),"s2"),false);
        assertThat(dispatch(op).path("status").asText()).isEqualTo("SUCCEEDED");
        verify(release,atLeastOnce()).requireAudio(eq(user),eq("general-communication"),eq(1),anyString());
    }

    @Test void invalidHistoricalVoiceSnapshotDoesNotBlockReadingRestrictedLesson() {
        doReturn(recipe()).when(engineConfig).ttsRecipe();
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        var content=(ObjectNode)tree(db.queryForObject("SELECT private_json FROM scenario_activities WHERE id=?",String.class,aid));
        ((ObjectNode)content.path("activity").path("segments").get(0)).put("textEn","[REDACTED]");
        db.update("UPDATE scenario_activities SET private_json=? WHERE id=?",content.toString(),aid);
        doThrow(new ScenarioFailure(503,"UNSUPPORTED_PROGRAM_MODE","Chương trình chưa mở.")).when(release).requireAudio(eq(user),anyString(),anyInt(),anyString());
        var saved=engine.session(user,sid);
        assertThat(saved.path("activities").get(0).path("segments").get(0).path("canPlay").asBoolean()).isFalse();
        assertThat(saved.path("activities").get(0).path("questions")).hasSize(2);
    }

    @Test void preparationDoesNotCountAndOnlyReadyObservedPlaybackBumpsVersionOncePerReceipt() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        long version=session.path("version").asLong(); var request=new ScenarioEngine.PlayRequest(version,"s1");
        assertThatThrownBy(() -> engine.played(user,sid,aid,id(),request)).isInstanceOf(ScenarioFailure.class);
        String key=id(); var pending=engine.prepareAudio(user,sid,aid,key,request,false);
        assertThat(pending.path("sessionVersion").asLong()).isEqualTo(version);
        assertThatThrownBy(() -> engine.played(user,sid,aid,id(),request)).isInstanceOf(ScenarioFailure.class);
        assertThat(engine.prepareAudio(user,sid,aid,key,request,false).toString()).isEqualTo(pending.toString());
        var ready=dispatch(pending);
        assertThat(engine.prepareAudio(user,sid,aid,id(),request,false).path("mediaId").asText()).isEqualTo(ready.path("mediaId").asText());
        var untouched=engine.session(user,sid);
        assertThat(untouched.path("version").asLong()).isEqualTo(version);
        assertThat(untouched.path("activities").get(0).path("plays").asInt()).isZero();
        clearInvocations(ai);
        String observedKey=id(); var observed=engine.played(user,sid,aid,observedKey,request);
        assertThat(engine.played(user,sid,aid,observedKey,request).toString()).isEqualTo(observed.toString());
        assertThat(observed.path("sessionVersion").asLong()).isEqualTo(version+1);
        assertThat(engine.session(user,sid).path("activities").get(0).path("plays").asInt()).isEqualTo(1);
        verifyNoInteractions(ai);
        assertThatThrownBy(() -> engine.prepareAudio(user,sid,aid,id(),request,false)).isInstanceOf(ScenarioFailure.class);
        assertThatThrownBy(() -> engine.played(user,sid,aid,id(),request)).isInstanceOf(ScenarioFailure.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=? AND kind='TTS'",Integer.class,user)).isEqualTo(1);
    }

    @Test void automaticPreparationDoesNotRetryFailedOrUnknownButExplicitRetryRemainsBounded() {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        var request=new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1");
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","FAILED")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        dispatch(engine.prepareAudio(user,sid,aid,id(),request,false));
        assertThat(engine.prepareAudio(user,sid,aid,id(),request,false).path("status").asText()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=? AND kind='TTS'",Integer.class,user)).isEqualTo(1);
        for(int i=0;i<2;i++) dispatch(engine.prepareAudio(user,sid,aid,id(),request,true));
        assertThat(engine.prepareAudio(user,sid,aid,id(),request,true).path("status").asText()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=? AND kind='TTS'",Integer.class,user)).isEqualTo(3);
        doAnswer(call -> object().put("schemaVersion","1").put("jobId",(String)call.getArgument(1)).put("status","UNKNOWN")).when(ai).submitScenario(anyLong(),anyString(),any(),any());
        var second=new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s2");
        dispatch(engine.prepareAudio(user,sid,aid,id(),second,false));
        assertThat(engine.prepareAudio(user,sid,aid,id(),second,true).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=? AND kind='TTS'",Integer.class,user)).isEqualTo(4);
        assertThat(engine.session(user,sid).path("activities").get(0).path("plays").asInt()).isZero();
    }

    @Test void preparationAndObservedPlaybackKeepOwnerOrderCsrfAndStrictRequestValidation() throws Exception {
        var session=active(); String sid=session.path("sessionId").asText(),aid=session.path("activities").get(0).path("activityId").asText();
        var request=new ScenarioEngine.PlayRequest(session.path("version").asLong(),"s1");
        var other=users.saveAndFlush(new AppUser(id()+"@audio.test","Other","unused",Instant.now()));
        String later=session.path("activities").get(1).path("activityId").asText();
        for(String action:List.of("prepare","played")) {
            String path="/api/v2/scenarios/sessions/"+sid+"/activities/"+aid+"/"+action;
            mvc.perform(post(path).with(user(DictaLabPrincipal.from(owner))).header("Idempotency-Key",id())
                    .contentType("application/json").content(json(request))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user(DictaLabPrincipal.from(other))).with(csrf()).header("Idempotency-Key",id())
                    .contentType("application/json").content(json(request))).andExpect(status().isNotFound());
            mvc.perform(post(path).with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                    .contentType("application/json").content("{\"expectedVersion\":1,\"segmentId\":\"s1\",\"extra\":true}")).andExpect(status().isBadRequest());
            mvc.perform(post("/api/v2/scenarios/sessions/"+sid+"/activities/"+later+"/"+action).with(user(DictaLabPrincipal.from(owner)))
                    .with(csrf()).header("Idempotency-Key",id()).contentType("application/json").content(json(request))).andExpect(status().isBadRequest());
        }
        var registered=mvc.perform(post("/api/v2/scenarios/sessions/"+sid+"/activities/"+aid+"/prepare")
                .with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                .contentType("application/json").content(json(request))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn();
        dispatch(tree(registered.getResponse().getContentAsString()));
        mvc.perform(post("/api/v2/scenarios/sessions/"+sid+"/activities/"+aid+"/played")
                .with(user(DictaLabPrincipal.from(owner))).with(csrf()).header("Idempotency-Key",id())
                .contentType("application/json").content(json(request))).andExpect(status().isOk());
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
