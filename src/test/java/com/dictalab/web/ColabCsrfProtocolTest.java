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
class ColabCsrfProtocolTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired ObjectMapper json;

    @Test void reporterCanUsePublicCsrfCookieWithScopedKeyWithoutAnAdminLoginCookie() throws Exception {
        var admin = new AppUser("colab-csrf-" + UUID.randomUUID() + "@dictalab.local", "Test", "unused", Instant.now());
        admin.promoteToAdmin(Instant.now()); admin = users.save(admin);
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
    }
}
