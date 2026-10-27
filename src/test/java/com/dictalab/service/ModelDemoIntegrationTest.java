package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"dictalab.model-demo.enabled=true","dictalab.model-demo.poll-delay-ms=600000",
        "dictalab.ai.budget.enabled=true"})
@AutoConfigureMockMvc
class ModelDemoIntegrationTest {
    @Autowired ModelDemoService demo;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired MockMvc mvc;
    @MockitoBean AiPlatformClient ai;
    @MockitoBean ConversationService conversation;
    AppUser owner;

    @BeforeEach void fixture() {
        owner=users.save(new AppUser(UUID.randomUUID()+"@model-demo.test","Demo","unused",Instant.now()));
        given(conversation.modelDemoContext(eq(owner.getId()),any(),any(),any())).willReturn(
                json.readTree("{\"difficulty\":\"A1\",\"scenario\":\"Cafe\",\"turnNumber\":1}"));
    }

    JsonNode submit(UUID key, UUID session, String mode) {
        return demo.submit(owner.getId(),key,session,"cafe","A1",mode,"full07",false);
    }

    JsonNode completeResult(String mode) {
        var value=json.createObjectNode().put("mode",mode).put("evaluationOnly",true).put("promptHash",ModelDemoService.PROMPT_SHA)
                .put("baseRevision",ModelDemoService.BASE_REVISION).put("adapterSha256",ModelDemoService.ADAPTER_SHA);
        var arms=value.putArray("arms");
        for (String variant:mode.equals("compare")?List.of("base","full07"):List.of("full07")) {
            var arm=arms.addObject().put("variant",variant).put("valid",true);
            arm.putObject("task").put("answer","private answer"); arm.putObject("candidate").put("raw","private candidate");
            arm.put("raw","private raw");
            var calls=arm.putArray("calls");
            for (String purpose:List.of("content","translation")) {
                var call=calls.addObject().put("purpose",purpose).put("provider","local_cpu").put("model","Qwen/Qwen3.5-4B")
                        .put("variant",variant).put("baseRevision",ModelDemoService.BASE_REVISION);
                if (variant.equals("full07")) call.put("adapterSha256",ModelDemoService.ADAPTER_SHA);
            }
        }
        return value;
    }

    @Test void submissionAndQuotaAreIdempotentAndRemainQueuedWithoutAi() {
        UUID key=UUID.randomUUID();
        var first=submit(key,null,"compare");
        assertThat(submit(key,null,"compare").path("id")).isEqualTo(first.path("id"));
        assertThat(db.queryForObject("SELECT calls FROM ai_daily_budget WHERE scope=?",Integer.class,"LOCAL_USER:"+owner.getId())).isEqualTo(1);
        given(ai.submitDemo(eq(owner.getId()),anyString(),any(),any())).willThrow(new IllegalStateException("offline"));
        demo.dispatch();
        var persisted=demo.get(owner.getId(),first.path("id").asText());
        assertThat(persisted.path("status").asText()).isEqualTo("PENDING");
        assertThat(persisted.path("error").asText()).isEqualTo("AI_SERVICE_UNAVAILABLE");
        assertThatThrownBy(()->demo.submit(owner.getId(),key,null,"cafe","A2","compare","full07",false))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->demo.get(owner.getId()+1000000,first.path("id").asText())).isInstanceOf(ResponseStatusException.class);
    }

    @Test void resultIsSavedAndComparisonCannotBecomePractice() {
        var first=submit(UUID.randomUUID(),null,"compare");
        String id=first.path("id").asText();
        var reply=json.createObjectNode().put("jobId",id).put("schemaVersion","1").put("status","DONE");
        reply.set("result",completeResult("compare"));
        given(ai.submitDemo(eq(owner.getId()),eq(id),any(),any())).willReturn(reply);
        demo.dispatch();
        assertThat(demo.get(owner.getId(),id).path("result").path("arms").size()).isEqualTo(2);
        assertThatThrownBy(()->demo.accept(owner.getId(),id)).isInstanceOf(ResponseStatusException.class);
        then(conversation).should(never()).acceptModelDemo(anyLong(),anyString(),anyString(),any(),any());
    }

    @Test void practiceResultsNeverLeakAnswersAndDelegationKeepsOwnership() {
        UUID session=UUID.randomUUID();
        db.update("INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json) VALUES (?,?,'cafe','A1','{}')",session.toString(),owner.getId());
        String id=submit(UUID.randomUUID(),session,"practice").path("id").asText();
        var reply=json.createObjectNode().put("jobId",id).put("schemaVersion","1").put("status","DONE");
        reply.set("result",completeResult("practice"));
        given(ai.submitDemo(eq(owner.getId()),eq(id),any(),any())).willReturn(reply);
        demo.dispatch();
        assertThat(demo.get(owner.getId(),id).toString()).doesNotContain("private answer","private candidate","private raw");
        demo.accept(owner.getId(),id);
        then(conversation).should().acceptModelDemo(eq(owner.getId()),eq(session.toString()),eq(id),any(),any());
    }

    @Test void compareRejectsCloudEvenIfAiMislabelsResult() {
        JsonNode payload=json.readTree("{\"mode\":\"compare\",\"variant\":\"full07\",\"allowFallback\":false}");
        var result=completeResult("compare");
        ((tools.jackson.databind.node.ObjectNode)result.path("arms").path(0)).put("variant","cloud");
        assertThatThrownBy(()->ModelDemoService.validateResult(payload,result)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void csrfAndLoginAreRequiredBeforeSubmission() throws Exception {
        mvc.perform(post("/api/v1/model-demo/jobs").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/model-demo/jobs").with(user(DictaLabPrincipal.from(owner)))
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/model-demo/jobs").with(user(DictaLabPrincipal.from(owner))).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
