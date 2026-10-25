package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ColabTelemetryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real cookie/XOR CSRF handshake, deliberately without the csrf() test repository override. */
@SpringBootTest(properties = {"dictalab.training.colab.enabled=true", "dictalab.training.colab.directory=target/test-colab-csrf-protocol-${random.uuid}"})
@AutoConfigureMockMvc
class ColabCsrfProtocolIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired ObjectMapper json;

    @Test void reporterCanUsePublicCsrfCookieWithScopedKeyWithoutAnAdminLoginCookie() throws Exception {
        var admin = users.findByEmail("hoangptkdhy@gmail.com")
                .orElseGet(() -> new AppUser("hoangptkdhy@gmail.com", "Test", "unused", Instant.now()));
        if (!"ADMIN".equals(admin.getRole())) admin.promoteToAdmin(Instant.now());
        admin = users.save(admin);
        var response = mvc.perform(get("/api/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readTree(response.getContentAsString());
        org.assertj.core.api.Assertions.assertThat(response.getCookies()).isNotEmpty();
        var registration = mvc.perform(post("/api/admin/training/colab").secure(true)
                        .with(user(DictaLabPrincipal.from(admin))).cookie(response.getCookies())
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .contentType("application/json").content("{\"name\":\"Real CSRF fixture\",\"notebookUrl\":\"https://colab.research.google.com/drive/abcdefghijklm\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var connection = json.readTree(registration.getContentAsString());
        mvc.perform(post("/api/training/telemetry/" + connection.path("id").asText()).secure(true)
                        .cookie(response.getCookies()).header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .header("Authorization", "Bearer " + connection.path("token").asText())
                        .contentType("application/json").content(json.writeValueAsBytes(ColabTelemetryTest.report(json))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        var resumed = ColabTelemetryTest.report(json);
        resumed.put("sequence", 2).put("status", "RUNNING");
        resumed.putObject("attempt").put("id", "attempt-" + "a".repeat(32)).put("fromStep", 1);
        ((tools.jackson.databind.node.ObjectNode)resumed.path("bindings")).put("trainerHash", "e".repeat(64));
        String id = connection.path("id").asText();
        mvc.perform(post("/api/training/telemetry/" + id).secure(true)
                        .cookie(response.getCookies()).header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .header("Authorization", "Bearer " + connection.path("token").asText())
                        .contentType("application/json").content(json.writeValueAsBytes(resumed)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false)).andExpect(jsonPath("$.pendingApproval").value(true));
        var pending = mvc.perform(get("/api/admin/training/runs/" + id).with(user(DictaLabPrincipal.from(admin))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String fingerprint = json.readTree(pending.getContentAsString()).path("pendingAttempt").path("fingerprint").asText();
        mvc.perform(post("/api/admin/training/colab/" + id + "/approve-attempt").secure(true)
                        .with(user(DictaLabPrincipal.from(admin))).cookie(response.getCookies())
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .contentType("application/json").content(json.writeValueAsBytes(java.util.Map.of("fingerprint", fingerprint))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/training/telemetry/" + id).secure(true)
                        .cookie(response.getCookies()).header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .header("Authorization", "Bearer " + connection.path("token").asText())
                        .contentType("application/json").content(json.writeValueAsBytes(resumed)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(get("/api/admin/training/colab/" + id + "/attempts/legacy").with(user(DictaLabPrincipal.from(admin))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bindings.trainerHash").value("b".repeat(64)));
        mvc.perform(get("/api/admin/training/colab/" + id + "/attempts/legacy").header("Authorization", "Bearer " + connection.path("token").asText()))
                .andExpect(status().isUnauthorized());
    }
}
