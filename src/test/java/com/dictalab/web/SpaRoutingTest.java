package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm tra Spring layer: các SPA path phải trả về 200 OK (forward → index.html),
 * /api/** trả JSON đúng status, tài nguyên tĩnh phục vụ bình thường.
 *
 * Lưu ý: MockMvc xử lý forward:/index.html bằng cách trả 200 OK nhưng không set
 * Content-Type header trong mock context (khác trình duyệt thật). Vì vậy chỉ
 * assert status cho SPA routes; Content-Type chỉ assert cho /api/** và static resources.
 */
@SpringBootTest(properties = {"dictalab.training.colab.enabled=true", "dictalab.training.colab.directory=target/test-colab-security-${random.uuid}"})
@AutoConfigureMockMvc
class SpaRoutingTest {

    @Test
    void colabIngestionNeedsCsrfScopedKeyAndHttpsWhileAdminRemainsProtected() throws Exception {
        String registration = "{\"name\":\"Security fixture\",\"notebookUrl\":\"https://colab.research.google.com/drive/abcdefghijklm\"}";
        mockMvc.perform(post("/api/admin/training/colab").with(csrf()).contentType("application/json").content(registration))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/training/colab").with(user(DictaLabPrincipal.from(user))).with(csrf()).contentType("application/json").content(registration))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/training/colab").with(user(DictaLabPrincipal.from(adminUser))).contentType("application/json").content(registration))
                .andExpect(status().isForbidden());
        var response = mockMvc.perform(post("/api/admin/training/colab").with(user(DictaLabPrincipal.from(adminUser))).with(csrf()).contentType("application/json").content(registration))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var json = new tools.jackson.databind.ObjectMapper();
        var connection = json.readTree(response.getContentAsString());
        String id = connection.path("id").asText(), auth = "Bearer " + connection.path("token").asText();
        String payload = json.writeValueAsString(com.dictalab.service.ColabTelemetryTest.report(json));
        String endpoint = "/api/training/telemetry/" + id;
        mockMvc.perform(post(endpoint).secure(true).header("Authorization", auth).contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(endpoint).secure(true).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(endpoint).with(csrf()).header("Authorization", auth).contentType("application/json").content(payload))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(endpoint).secure(true).with(csrf()).header("Authorization", auth).contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        mockMvc.perform(get("/api/admin/training/runs/" + id).header("Authorization", auth)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/training/runs/" + id).with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("colab"))
                .andExpect(jsonPath("$.completionVerified").value(false)).andExpect(jsonPath("$.canResume").value(false));
        mockMvc.perform(get("/api/admin/training").with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.datasets[0].records.train").value(800))
                .andExpect(jsonPath("$.datasets[0].approved").value(false)).andExpect(jsonPath("$.datasets[0].reportedApproved").value(true));
        mockMvc.perform(post("/api/admin/training/runs/" + id + "/start").with(user(DictaLabPrincipal.from(adminUser))).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/admin/colab_reporter.py")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/admin/colab_reporter.py").with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Read-only Colab telemetry")));
        mockMvc.perform(post("/api/admin/training/colab/" + id + "/revoke").with(user(DictaLabPrincipal.from(adminUser))).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post(endpoint).secure(true).with(csrf()).header("Authorization", auth).contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    private AppUser user;
    private AppUser adminUser;

    @BeforeEach
    void setUp() {
        user = userRepository.save(new AppUser(
                "spa-route-user-" + UUID.randomUUID() + "@dictalab.local",
                "Routing User", "unused", Instant.now()
        ));
        AppUser newAdmin = new AppUser(
                "spa-route-admin-" + UUID.randomUUID() + "@dictalab.local",
                "Routing Admin", "unused", Instant.now()
        );
        newAdmin.promoteToAdmin(Instant.now());
        adminUser = userRepository.save(newAdmin);
    }

    // ── SPA routes trả về 200 OK (forward → index.html) ──────────────────

    @Test
    void loginPageReturnsOk() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void registerPageReturnsOk() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk());
    }

    @Test
    void catalogPageReturnsOk() throws Exception {
        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk());
    }

    @Test
    void practicePageWithSessionIdReturnsOk() throws Exception {
        mockMvc.perform(get("/practice/some-session-id-123")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isOk());
    }

    @Test
    void historyListPageReturnsOk() throws Exception {
        mockMvc.perform(get("/history")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isOk());
    }

    @Test
    void historyDetailPageReturnsOk() throws Exception {
        mockMvc.perform(get("/history/some-session-id-456")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isOk());
    }

    @Test
    void dashboardPageReturnsOk() throws Exception {
        mockMvc.perform(get("/dashboard")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isOk());
    }

    @Test
    void roomsLobbyPageReturnsOk() throws Exception {
        mockMvc.perform(get("/rooms")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isOk());
    }

    @Test
    void roomDetailPageReturnsOk() throws Exception {
        mockMvc.perform(get("/rooms/some-room-id")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isOk());
    }

    @Test
    void roomJoinPageReturnsOk() throws Exception {
        mockMvc.perform(get("/rooms/join/some-invite-code")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isOk());
    }

    @Test
    void adminLessonsListPageReturnsOkForAdmin() throws Exception {
        mockMvc.perform(get("/admin/lessons")
                        .with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin.html"));
    }

    @Test
    void adminNewLessonPageReturnsOkForAdmin() throws Exception {
        mockMvc.perform(get("/admin/lessons/new")
                        .with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin.html"));
    }

    @Test
    void adminLessonDetailPageReturnsOkForAdmin() throws Exception {
        mockMvc.perform(get("/admin/lessons/1")
                        .with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin.html"));
    }

    @Test
    void adminPagesAndDocumentRequireAdminRole() throws Exception {
        for (String path : new String[]{"/admin", "/admin/", "/admin.html", "/admin/lessons",
                "/admin/lessons/new", "/admin/lessons/1", "/admin/users", "/admin/support", "/admin/listening",
                "/admin/training", "/admin/training/", "/admin/training.html"}) {
            mockMvc.perform(get(path)).andExpect(status().isFound())
                    .andExpect(redirectedUrl("/login?next=" + java.net.URLEncoder.encode(
                            path, java.nio.charset.StandardCharsets.UTF_8)));
            mockMvc.perform(get(path).with(user(DictaLabPrincipal.from(user))))
                    .andExpect(status().isForbidden()).andExpect(forwardedUrl("/error/403.html"));
        }
        mockMvc.perform(get("/admin/lessons/42?page=3"))
                .andExpect(redirectedUrl("/login?next=%2Fadmin%2Flessons%2F42%3Fpage%3D3"));
    }

    @Test
    void trainingDocumentAndRouteAreAdminOnlyAndColabWorksWithoutLocalRuntime() throws Exception {
        var admin = user(DictaLabPrincipal.from(adminUser));
        mockMvc.perform(get("/admin/training").with(admin))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/admin/training.html"));
        mockMvc.perform(get("/admin/training.html").with(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"studio-main\"")));
        mockMvc.perform(get("/api/admin/training")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/training").with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/training").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.localConnected").value(false))
                .andExpect(jsonPath("$.capabilities.create").value(false)).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void trainingCommandsRequireAdminAndCsrfBeforeReachingRuntime() throws Exception {
        for (String path : new String[]{"/api/admin/training/runs", "/api/admin/training/runs/abc/start",
                "/api/admin/training/runs/abc/resume", "/api/admin/training/runs/abc/pause", "/api/admin/training/runs/abc/stop"}) {
            mockMvc.perform(post(path).with(user(DictaLabPrincipal.from(adminUser)))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(path).with(user(DictaLabPrincipal.from(user))).with(csrf())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(path).with(user(DictaLabPrincipal.from(adminUser))).with(csrf())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    void adminCanLoadSeparateDocumentAndAccountRoutes() throws Exception {
        mockMvc.perform(get("/admin").with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(redirectedUrl("/admin/lessons"));
        for (String path : new String[]{"/admin/users", "/admin/support", "/admin/listening"}) {
            mockMvc.perform(get(path).with(user(DictaLabPrincipal.from(adminUser))))
                    .andExpect(status().isOk()).andExpect(forwardedUrl("/admin.html"));
        }
        mockMvc.perform(get("/admin.html").with(user(DictaLabPrincipal.from(adminUser))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-area=\"admin\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"catalog-view\""))));
        mockMvc.perform(get("/index.html"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"admin-view\""))));
    }

    // ── Unauthenticated access to SPA routes ──────────────────────────────

    @Test
    void unauthenticatedUserCanOpenCatalogRoute() throws Exception {
        // SPA path permit all → trả 200, frontend render trang login
        mockMvc.perform(get("/catalog"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedUserCanOpenPracticeRoute() throws Exception {
        mockMvc.perform(get("/practice/any-session"))
                .andExpect(status().isOk());
    }

    // ── /api/** KHÔNG bị forward → trả JSON với đúng status ──────────────

    @Test
    void apiCsrfEndpointIsNotForwardedToSpa() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void unauthenticatedApiCallReturnsJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value("Vui lòng đăng nhập để tiếp tục."));
    }

    @Test
    void adminApiRequiresAdminRole() throws Exception {
        // user thường → 403 Forbidden
        mockMvc.perform(get("/api/admin/lessons")
                        .with(user(DictaLabPrincipal.from(user))))
                .andExpect(status().isForbidden());
    }

    // ── Tài nguyên tĩnh không bị ảnh hưởng ──────────────────────────────

    @Test
    void cssResourceIsServedDirectly() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    void jsResourceIsServedDirectly() throws Exception {
        // text/javascript và application/javascript đều hợp lệ theo RFC 9239
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk());
    }

    @Test
    void callAvatarsAreAvailableBeforeLogin() throws Exception {
        mockMvc.perform(get("/images/avatar-host.svg"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/images/avatar-guest.svg"))
                .andExpect(status().isOk());
    }

    @Test
    void faviconIsAvailableAndUnknownStaticResourceIsNotAServerError() throws Exception {
        mockMvc.perform(get("/favicon.svg")).andExpect(status().isOk());
        mockMvc.perform(get("/css/does-not-exist.css")).andExpect(status().isNotFound());
    }
}
