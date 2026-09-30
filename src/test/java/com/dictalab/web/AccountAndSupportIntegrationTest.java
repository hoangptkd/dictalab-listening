package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ListeningComprehensionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountAndSupportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ListeningComprehensionService listening;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    private AppUser create(String name) {
        return users.save(new AppUser(name + "-" + UUID.randomUUID() + "@test.local", name,
                encoder.encode("oldpass123"), Instant.now()));
    }

    @Test
    void passwordChangeRevokesPriorSessionAndResetDoesNotRevealAccount() throws Exception {
        AppUser learner = create("learner");
        DictaLabPrincipal old = DictaLabPrincipal.from(learner);
        mvc.perform(post("/api/account/password").with(user(old)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"oldpass123\",\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").with(user(old))).andExpect(status().isUnauthorized());
        mvc.perform(get("/login").with(user(old))).andExpect(status().isOk());
        assertThat(encoder.matches("newpass123", users.findById(learner.getId()).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"unknown@test.local\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + learner.getEmail() + "\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void adminCanLockUserButCannotRemoveLastAdmin() throws Exception {
        AppUser admin = create("admin");
        admin.promoteToAdmin(Instant.now());
        users.saveAndFlush(admin);
        AppUser learner = create("target");
        DictaLabPrincipal adminPrincipal = DictaLabPrincipal.from(admin);
        DictaLabPrincipal oldLearner = DictaLabPrincipal.from(learner);
        mvc.perform(put("/api/admin/users/" + learner.getId() + "/state")
                .with(user(adminPrincipal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"Kiểm tra bảo mật\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/auth/me").with(user(oldLearner))).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/users/" + admin.getId() + "/role")
                .with(user(adminPrincipal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void supportReportRequiresOwnershipOfReferencedListeningSession() throws Exception {
        AppUser owner = create("owner");
        AppUser other = create("other");
        Long unit = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        String sessionId = (String) listening.start(owner.getId(), unit, "ONE_PASS", false).get("sessionId");
        String body = "{\"category\":\"SCORE\",\"subject\":\"Điểm chưa đúng\","
                + "\"description\":\"Xin kiểm tra phần nhớ ý\",\"referenceType\":\"LISTENING\","
                + "\"referenceId\":\"" + sessionId + "\"}";
        mvc.perform(post("/api/support/tickets").with(user(DictaLabPrincipal.from(other))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        String response = mvc.perform(post("/api/support/tickets").with(user(DictaLabPrincipal.from(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        long ticket = json.readTree(response).path("id").asLong();
        mvc.perform(get("/api/admin/support/tickets/" + ticket + "/grading")
                .with(user(DictaLabPrincipal.from(owner)))).andExpect(status().isForbidden());
    }

    @Test
    void adminCanDeleteOnlyAccountThatRequestedDeletionAndAllLearningData() throws Exception {
        AppUser admin = create("admin-delete");
        admin.promoteToAdmin(Instant.now());
        users.saveAndFlush(admin);
        AppUser learner = create("delete-me");
        Long unit = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        listening.start(learner.getId(), unit, "ONE_PASS", false);
        String ticketBody = "{\"category\":\"ACCOUNT_DELETE\",\"subject\":\"Xóa tài khoản\","
                + "\"description\":\"Tôi muốn xóa tài khoản và dữ liệu học\"}";
        String response = mvc.perform(post("/api/support/tickets").with(user(DictaLabPrincipal.from(learner)))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ticketBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long ticket = json.readTree(response).path("id").asLong();
        mvc.perform(post("/api/admin/support/tickets/" + ticket + "/delete-account")
                .with(user(DictaLabPrincipal.from(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"wrong@test.local\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.existsById(learner.getId())).isTrue();
        mvc.perform(post("/api/admin/support/tickets/" + ticket + "/delete-account")
                .with(user(DictaLabPrincipal.from(admin))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + learner.getEmail() + "\"}"))
                .andExpect(status().isOk());
        assertThat(users.existsById(learner.getId())).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM listening_sessions WHERE user_id=?", Integer.class,
                learner.getId())).isZero();
    }

    @Test
    void allowlistedAccountNeedsOneTimeEmailProofBeforeAdminAccess() throws Exception {
        AppUser candidate = users.save(new AppUser("verified-admin@dictalab.local", "Candidate",
                encoder.encode("oldpass123"), Instant.now()));
        DictaLabPrincipal old = DictaLabPrincipal.from(candidate);
        mvc.perform(get("/api/admin/users").with(user(old))).andExpect(status().isForbidden());
        String token = UUID.randomUUID().toString();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        db.update("""
                INSERT INTO account_tokens(token_hash,user_id,purpose,created_at,expires_at)
                VALUES (?,?,'VERIFY_EMAIL',?,?)
                """, hash, candidate.getId(), Instant.now(), Instant.now().plusSeconds(1800));
        mvc.perform(post("/api/auth/email/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/email/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").with(user(old))).andExpect(status().isUnauthorized());
        DictaLabPrincipal verified = DictaLabPrincipal.from(users.findById(candidate.getId()).orElseThrow());
        assertThat(verified.role()).isEqualTo("ADMIN");
        mvc.perform(get("/api/admin/users").with(user(verified))).andExpect(status().isOk());
    }

    @Test
    @Transactional
    void supportQueuesArePaginatedForLearnerAndAdmin() throws Exception {
        AppUser learner = create("ticket-page");
        AppUser admin = create("ticket-admin");
        admin.promoteToAdmin(Instant.now());
        users.saveAndFlush(admin);
        for (int index = 0; index < 21; index++) {
            db.update("""
                    INSERT INTO support_tickets(user_id,category,subject,description)
                    VALUES (?,'OTHER',?,?)
                    """, learner.getId(), "Request " + index, "Pagination check");
        }
        mvc.perform(get("/api/support/tickets?page=0").with(user(DictaLabPrincipal.from(learner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tickets.length()").value(20))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/support/tickets?page=1").with(user(DictaLabPrincipal.from(learner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tickets.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/admin/support/tickets?status=OPEN&page=0")
                .with(user(DictaLabPrincipal.from(admin))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tickets.length()").value(20))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/support/tickets?page=-1").with(user(DictaLabPrincipal.from(learner))))
                .andExpect(status().isBadRequest());
    }
}
