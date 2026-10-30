package com.dictalab.web;

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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"dictalab.billing.enabled=true", "dictalab.conversation.enabled=true",
        "dictalab.coach.enabled=false", "dictalab.ai.queue.enabled=false",
        "dictalab.billing.public-base-url=https://dictalab.example", "dictalab.billing.client-id=test-client",
        "dictalab.billing.api-key=test-api", "dictalab.billing.checksum-key=test-checksum"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ConversationAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @MockitoBean GeminiClient ai;
    @MockitoBean CartesiaClient tts;
    @MockitoBean AiPlatformClient platform;
    @MockitoSpyBean AiUsageBudget budget;
    AppUser learner;

    @BeforeEach void setup() {
        learner = users.saveAndFlush(new AppUser(UUID.randomUUID() + "@access.test", "Learner", "unused", Instant.now()));
        when(ai.configured()).thenReturn(true);
        when(tts.enabled()).thenReturn(true);
        when(tts.synthesize(anyString())).thenReturn(new byte[]{1, 2, 3});
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freeAndExpiredAccountsAreStoppedBeforeCreatingOrQueuingAudio(boolean expired) throws Exception {
        if (expired) premiumUntil(Instant.now().minusSeconds(60));
        mvc.perform(get("/api/v1/conversation").with(user(DictaLabPrincipal.from(learner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.premiumRequired").value(true));
        mvc.perform(post("/api/v1/conversation/sessions").with(user(DictaLabPrincipal.from(learner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"cafe\",\"difficulty\":\"A1\"}"))
                .andExpect(status().isPaymentRequired()).andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.message").value("Tính năng AI dành cho Premium. Bạn có thể nâng cấp tại Tài khoản."));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_sessions WHERE user_id=?", Integer.class, learner.getId())).isZero();

        String id = existingSession();
        play(id).andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.message").value(
                "Tính năng AI dành cho Premium. Bạn có thể nâng cấp tại Tài khoản."));
        mvc.perform(post("/api/v1/conversation/sessions/" + id + "/next")
                .with(user(DictaLabPrincipal.from(learner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"turnNumber\":1}"))
                .andExpect(status().isPaymentRequired());
        assertThat(db.queryForObject("SELECT version FROM ai_conversation_sessions WHERE id=?", Long.class, id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_background_jobs WHERE conversation_id=?", Integer.class, id)).isZero();
        verify(budget, never()).reserve(learner.getId(), true);
        verify(tts, never()).synthesize(anyString());
        verify(platform, never()).complete(anyString(), anyLong(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void premiumAndAdministratorCanCreateAndPlayWithoutDoubleCharging(boolean admin) throws Exception {
        if (admin) {
            learner.promoteToAdmin(Instant.now()); users.saveAndFlush(learner);
        } else premiumUntil(Instant.now().plusSeconds(3600));
        mvc.perform(get("/api/v1/conversation").with(user(DictaLabPrincipal.from(learner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.premiumRequired").value(false));
        mvc.perform(post("/api/v1/conversation/sessions").with(user(DictaLabPrincipal.from(learner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"cafe\",\"difficulty\":\"A1\"}"))
                .andExpect(status().isOk());
        String id = existingSession();
        play(id).andExpect(status().isOk()).andExpect(jsonPath("$.audioStatus").value("READY"));
        play(id).andExpect(status().isOk()).andExpect(jsonPath("$.audioStatus").value("READY"));
        verify(tts, times(1)).synthesize(anyString());
        verify(budget, times(1)).reserve(learner.getId(), true);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM premium_daily_usage WHERE user_id=?", Integer.class, learner.getId())).isZero();
    }

    @Test void asynchronousQuotaRejectionKeepsItsMessageAndReleasesTheSession() throws Exception {
        premiumUntil(Instant.now().plusSeconds(3600));
        String id = existingSession();
        doThrow(new AiUsageBudget.Rejected(HttpStatus.TOO_MANY_REQUESTS, "Đã đạt giới hạn AI trong ngày."))
                .when(budget).reserve(learner.getId(), true);
        play(id).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.message").value("Đã đạt giới hạn AI trong ngày."));
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        verify(tts, never()).synthesize(anyString());
    }

    @Test void entitlementChangeDuringAudioAdmissionKeepsPaymentRequired() throws Exception {
        premiumUntil(Instant.now().plusSeconds(3600));
        String id = existingSession();
        doThrow(new AiUsageBudget.Rejected(HttpStatus.PAYMENT_REQUIRED, "Tính năng AI dành cho Premium."))
                .when(budget).reserve(learner.getId(), true);
        play(id).andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.message").value("Tính năng AI dành cho Premium."));
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        verify(tts, never()).synthesize(anyString());
    }

    @Test void actualAudioFailureStaysSanitizedAndIsLogged(CapturedOutput output) throws Exception {
        premiumUntil(Instant.now().plusSeconds(3600));
        String id = existingSession();
        when(tts.synthesize(anyString())).thenThrow(new IllegalStateException("private-provider-credential"));
        play(id).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value(
                "Chưa tạo được giọng nói. Bạn có thể thử nghe lại."));
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        assertThat(output.getAll()).contains("operation=audio", "errorType=IllegalStateException")
                .doesNotContain("private-provider-credential", "Please bring your ticket");
    }

    @Test void cachedAudioAndHistoryRemainAvailableAfterPremiumExpires() throws Exception {
        premiumUntil(Instant.now().minusSeconds(60));
        String id = existingSession();
        db.update("INSERT INTO ai_conversation_audio(session_id,turn_number,variant,wav) VALUES (?,1,'original',?)",
                id, new byte[]{1, 2, 3});
        play(id).andExpect(status().isOk()).andExpect(jsonPath("$.audioStatus").value("READY"));
        mvc.perform(get("/api/v1/conversation/sessions/" + id).with(user(DictaLabPrincipal.from(learner))))
                .andExpect(status().isOk());
        verify(budget, never()).reserve(learner.getId(), true);
        verify(tts, never()).synthesize(anyString());
    }

    private org.springframework.test.web.servlet.ResultActions play(String id) throws Exception {
        return mvc.perform(post("/api/v1/conversation/sessions/" + id + "/play")
                .with(user(DictaLabPrincipal.from(learner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"turnNumber\":1,\"variant\":\"original\",\"slow\":false}"));
    }

    private void premiumUntil(Instant until) {
        db.update("INSERT INTO premium_accounts(user_id,premium_until) VALUES (?,?)", learner.getId(), Timestamp.from(until));
    }

    private String existingSession() {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json) VALUES (?,?,'cafe','A1',?)",
                id, learner.getId(), """
                {"completed":false,"turns":[{"done":true,"attempts":[],"prompt":{
                "utteranceEn":"Please bring your ticket and meet the guide at the front desk.",
                "facts":[]}}]}
                """);
        return id;
    }
}
