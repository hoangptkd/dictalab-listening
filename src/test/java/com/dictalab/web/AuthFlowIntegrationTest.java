package com.dictalab.web;

import com.dictalab.security.RedisTokenStore;
import com.dictalab.security.JwtTokenService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Keep real cookie-CSRF tests separate from contexts mutated by the csrf() test shortcut.
@SpringBootTest(properties = "dictalab.auth.issuer=browser-auth-test")
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {
    // Broker-free regression suite; JwtRedisIntegrationTest covers the actual store and rotation.
    @MockitoBean RedisTokenStore tokenStore;

    @BeforeEach void allowIssuedSession() {
        org.mockito.BDDMockito.given(tokenStore.active(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong())).willReturn(true);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired private com.dictalab.repository.AppUserRepository users;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate db;

    @Test
    void guestsCanBrowseCatalogButCannotAccessPersonalProgressOrStartLearning() throws Exception {
        mockMvc.perform(get("/catalog")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/catalog/levels")).andExpect(status().isOk());
        for (String level : new String[]{"A1", "A2", "B1", "B2"}) {
            var response = mockMvc.perform(get("/api/v1/catalog/lessons").param("level", level))
                    .andExpect(status().isOk()).andReturn();
            var json = new tools.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString());
            org.assertj.core.api.Assertions.assertThat(json.size()).isPositive();
            for (var lesson : json) {
                org.assertj.core.api.Assertions.assertThat(lesson.path("progressPercent").asInt()).isZero();
                org.assertj.core.api.Assertions.assertThat(lesson.path("inProgress").asBoolean()).isFalse();
                org.assertj.core.api.Assertions.assertThat(lesson.path("completed").asBoolean()).isFalse();
                String origin = db.queryForObject("SELECT content_origin FROM lessons WHERE id=?", String.class, lesson.path("id").asLong());
                org.assertj.core.api.Assertions.assertThat(origin).isNotEqualTo("BRITISH_COUNCIL_PERSONAL");
            }
        }
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/practice-sessions").with(browserCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lessonId\":29}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/catalog/preview-exposure").with(browserCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lessonId\":29,\"audioUrl\":\"/audio/test.mp3\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicPoliciesAndHeadersWorkWithoutAuthentication() throws Exception {
        for (String path : new String[]{"/privacy", "/terms", "/help", "/policies.html", "/api/public/info"}) {
            mockMvc.perform(get(path).secure(true)).andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                    .andExpect(header().exists("Content-Security-Policy"))
                    .andExpect(header().exists("Permissions-Policy"))
                    .andExpect(header().exists("Strict-Transport-Security"));
        }
        mockMvc.perform(get("/api/public/info")).andExpect(jsonPath("$.supportEmail").isString());
    }

    @Test
    void onlyAnAdministratorWithBrowserCsrfCanOperateTheAiSwitch() throws Exception {
        mockMvc.perform(get("/api/admin/ai/budget")).andExpect(status().isUnauthorized());
        var owner = users.saveAndFlush(new com.dictalab.domain.AppUser(
                "ai-operator-" + java.util.UUID.randomUUID() + "@test.local", "Operator", "unused", java.time.Instant.now()));
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        tokens.issue(owner, response);
        var access = response.getCookie(JwtTokenService.ACCESS_COOKIE);
        mockMvc.perform(get("/api/admin/ai/budget").cookie(access)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/ai/enabled").cookie(access).with(browserCsrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        db.update("UPDATE app_users SET role='ADMIN' WHERE id=?", owner.getId());
        owner = users.findById(owner.getId()).orElseThrow();
        response = new org.springframework.mock.web.MockHttpServletResponse();
        tokens.issue(owner, response);
        access = response.getCookie(JwtTokenService.ACCESS_COOKIE);
        try {
            mockMvc.perform(get("/api/admin/ai/budget").cookie(access)).andExpect(status().isOk());
            mockMvc.perform(put("/api/admin/ai/enabled").cookie(access)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isForbidden()).andExpect(header().string("X-CSRF-Error", "1"));
            mockMvc.perform(put("/api/admin/ai/enabled").cookie(access).with(browserCsrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(put("/api/admin/ai/enabled").cookie(access).with(browserCsrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
            org.assertj.core.api.Assertions.assertThat(db.queryForObject(
                    "SELECT COUNT(*) FROM account_audit WHERE actor_id=? AND action='AI_EMERGENCY_SWITCH'",
                    Integer.class, owner.getId())).isEqualTo(1);
        } finally {
            db.update("UPDATE ai_budget_control SET enabled=TRUE WHERE id=1");
            db.update("UPDATE app_users SET role='USER' WHERE id=?", owner.getId());
        }
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor browserCsrf() throws Exception {
        var response = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String name = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.headerName");
        String value = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.token");
        return request -> {
            var cookies = new java.util.LinkedHashMap<String, jakarta.servlet.http.Cookie>();
            if (request.getCookies() != null) for (var cookie : request.getCookies()) cookies.put(cookie.getName(), cookie);
            cookies.put("XSRF-TOKEN", response.getCookie("XSRF-TOKEN"));
            request.setCookies(cookies.values().toArray(jakarta.servlet.http.Cookie[]::new));
            request.addHeader(name, value);
            return request;
        };
    }

    private MvcResult browserExchange(java.util.Map<String, jakarta.servlet.http.Cookie> jar,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        if (!jar.isEmpty()) request.cookie(jar.values().toArray(jakarta.servlet.http.Cookie[]::new));
        var result = mockMvc.perform(request).andReturn();
        // Apply Set-Cookie deletions instead of resurrecting a setup snapshot on the next request.
        for (var cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0 || cookie.getValue().isEmpty()) jar.remove(cookie.getName());
            else jar.put(cookie.getName(), cookie);
        }
        org.assertj.core.api.Assertions.assertThat(result.getRequest().getSession(false)).isNull();
        return result;
    }

    @Test
    void browserCookieJarKeepsCsrfAcrossReadsAndTwentyDictationAnswers() throws Exception {
        var jar = new java.util.LinkedHashMap<String, jakarta.servlet.http.Cookie>();
        var initial = browserExchange(jar, get("/api/auth/csrf")).getResponse();
        String csrfHeader = com.jayway.jsonpath.JsonPath.read(initial.getContentAsString(), "$.headerName");
        String csrfToken = com.jayway.jsonpath.JsonPath.read(initial.getContentAsString(), "$.token");
        var registration = browserExchange(jar, post("/api/auth/register").header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"displayName":"CSRF browser","email":"csrf-browser@dictalab.local","password":"listen123"}
                    """)).getResponse();
        org.assertj.core.api.Assertions.assertThat(registration.getStatus()).isEqualTo(201);
        var csrf = browserExchange(jar, get("/api/auth/csrf")).getResponse();
        csrfToken = com.jayway.jsonpath.JsonPath.read(csrf.getContentAsString(), "$.token");
        String cookieValue = jar.get("XSRF-TOKEN").getValue();
        var catalog = browserExchange(jar, get("/api/v1/catalog/lessons?level=A1")).getResponse();
        org.assertj.core.api.Assertions.assertThat(catalog.getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(jar).containsKey("XSRF-TOKEN");
        org.assertj.core.api.Assertions.assertThat(jar.get("XSRF-TOKEN").getValue()).isEqualTo(cookieValue);
        Number lesson = com.jayway.jsonpath.JsonPath.read(catalog.getContentAsString(), "$[0].id");
        var practice = browserExchange(jar, post("/api/v1/practice-sessions").header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"lessonId\":" + lesson + "}")).getResponse();
        org.assertj.core.api.Assertions.assertThat(practice.getStatus()).isEqualTo(200);
        String session = com.jayway.jsonpath.JsonPath.read(practice.getContentAsString(), "$.sessionId");
        Number sentence = com.jayway.jsonpath.JsonPath.read(practice.getContentAsString(), "$.sentence.sentenceId");
        java.util.List<java.util.Map<String, Object>> words = com.jayway.jsonpath.JsonPath.read(
                practice.getContentAsString(), "$.sentence.words");
        int word = ((Number) words.stream().filter(slot -> !Boolean.TRUE.equals(slot.get("prefilled")))
                .findFirst().orElseThrow().get("index")).intValue();
        String path = "/api/v1/practice-sessions/" + session + "/sentences/" + sentence;
        var played = browserExchange(jar, post(path + "/replay-events").header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"STARTED\",\"playbackSpeed\":1}")).getResponse();
        org.assertj.core.api.Assertions.assertThat(played.getStatus()).isEqualTo(200);
        for (int attempt = 0; attempt < 20; attempt++) {
            var answer = browserExchange(jar, post(path + "/words/" + word + "/checks").header(csrfHeader, csrfToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"fixture\",\"responseTimeMs\":1000}")).getResponse();
            org.assertj.core.api.Assertions.assertThat(answer.getStatus()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(answer.getHeader("X-CSRF-Error")).isNull();
            String saved = com.jayway.jsonpath.JsonPath.read(answer.getContentAsString(), "$.words[" + word + "].userInput");
            org.assertj.core.api.Assertions.assertThat(saved).isEqualTo("fixture");
            org.assertj.core.api.Assertions.assertThat(jar.get("XSRF-TOKEN").getValue()).isEqualTo(cookieValue);
            var read = browserExchange(jar, get("/api/v1/practice-sessions/" + session)).getResponse();
            org.assertj.core.api.Assertions.assertThat(read.getStatus()).isEqualTo(200);
        }
        for (String invalid : new String[]{null, "invalid"}) {
            var edit = post(path + "/words/" + word + "/checks").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"answer\":\"must-not-save\",\"responseTimeMs\":1000}");
            if (invalid != null) edit.header(csrfHeader, invalid);
            var rejected = browserExchange(jar, edit).getResponse();
            org.assertj.core.api.Assertions.assertThat(rejected.getStatus()).isEqualTo(403);
            org.assertj.core.api.Assertions.assertThat(rejected.getHeader("X-CSRF-Error")).isEqualTo("1");
        }
        var unchanged = browserExchange(jar, get("/api/v1/practice-sessions/" + session)).getResponse();
        String saved = com.jayway.jsonpath.JsonPath.read(unchanged.getContentAsString(), "$.sentence.words[" + word + "].userInput");
        org.assertj.core.api.Assertions.assertThat(saved).isEqualTo("fixture");
    }

    @Test
    void csrfChangesOnLoginAndLogoutButNotOnAccessRefresh() throws Exception {
        var jar = new java.util.LinkedHashMap<String, jakarta.servlet.http.Cookie>();
        var initial = browserExchange(jar, get("/api/auth/csrf")).getResponse();
        String csrfHeader = com.jayway.jsonpath.JsonPath.read(initial.getContentAsString(), "$.headerName");
        String csrfToken = com.jayway.jsonpath.JsonPath.read(initial.getContentAsString(), "$.token");
        var registration = browserExchange(jar, post("/api/auth/register").header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"displayName":"CSRF lifecycle","email":"csrf-lifecycle@dictalab.local","password":"listen123"}
                    """)).getResponse();
        org.assertj.core.api.Assertions.assertThat(registration.getStatus()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThat(jar).doesNotContainKey("XSRF-TOKEN");
        var fresh = browserExchange(jar, get("/api/auth/csrf")).getResponse();
        csrfToken = com.jayway.jsonpath.JsonPath.read(fresh.getContentAsString(), "$.token");
        String beforeLogin = jar.get("XSRF-TOKEN").getValue();
        var login = browserExchange(jar, post("/api/auth/login").header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"csrf-lifecycle@dictalab.local\",\"password\":\"listen123\"}")).getResponse();
        org.assertj.core.api.Assertions.assertThat(login.getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(jar).doesNotContainKey("XSRF-TOKEN");
        fresh = browserExchange(jar, get("/api/auth/csrf")).getResponse();
        org.assertj.core.api.Assertions.assertThat(jar.get("XSRF-TOKEN").getValue()).isNotEqualTo(beforeLogin);
        var stale = browserExchange(jar, put("/api/account/profile").header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Must not save\"}")).getResponse();
        org.assertj.core.api.Assertions.assertThat(stale.getStatus()).isEqualTo(403);
        org.assertj.core.api.Assertions.assertThat(stale.getHeader("X-CSRF-Error")).isEqualTo("1");
        csrfToken = com.jayway.jsonpath.JsonPath.read(fresh.getContentAsString(), "$.token");
        var claims = tokens.decode(jar.get(JwtTokenService.ACCESS_COOKIE).getValue());
        org.mockito.BDDMockito.given(tokenStore.session(claims.getClaimAsString("sid"))).willReturn(java.util.Map.of(
                "user", claims.getSubject(), "version", "0",
                "expires", Long.toString(java.time.Instant.now().plusSeconds(3600).getEpochSecond())));
        String beforeRefresh = jar.get("XSRF-TOKEN").getValue();
        var refreshed = browserExchange(jar, post("/api/auth/refresh").header(csrfHeader, csrfToken)).getResponse();
        org.assertj.core.api.Assertions.assertThat(refreshed.getStatus()).isEqualTo(204);
        org.assertj.core.api.Assertions.assertThat(jar.get("XSRF-TOKEN").getValue()).isEqualTo(beforeRefresh);
        var changed = browserExchange(jar, put("/api/account/profile").header(csrfHeader, csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"After refresh\"}")).getResponse();
        org.assertj.core.api.Assertions.assertThat(changed.getStatus()).isEqualTo(200);
        var logout = browserExchange(jar, post("/api/auth/logout").header(csrfHeader, csrfToken)).getResponse();
        org.assertj.core.api.Assertions.assertThat(logout.getStatus()).isEqualTo(204);
        org.assertj.core.api.Assertions.assertThat(jar).doesNotContainKeys("XSRF-TOKEN", JwtTokenService.ACCESS_COOKIE,
                JwtTokenService.REFRESH_COOKIE);
        org.assertj.core.api.Assertions.assertThat(browserExchange(jar, get("/api/auth/me")).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void cookieJwtStillRequiresCsrfAndIdleSessionCanRecoverBeforeSaving() throws Exception {
        // Use actual cookie/header CSRF values, not the csrf() shortcut, for browser recovery.
        var csrfResponse = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andReturn().getResponse();
        String csrfHeader = com.jayway.jsonpath.JsonPath.read(csrfResponse.getContentAsString(), "$.headerName");
        String csrfToken = com.jayway.jsonpath.JsonPath.read(csrfResponse.getContentAsString(), "$.token");
        var csrfCookie = csrfResponse.getCookie("XSRF-TOKEN");
        var registration = mockMvc.perform(post("/api/auth/register").cookie(csrfCookie)
                        .header(csrfHeader, csrfToken).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Before idle","email":"idle-recovery@dictalab.local","password":"listen123"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var access = registration.getCookie(JwtTokenService.ACCESS_COOKIE);
        var refresh = registration.getCookie(JwtTokenService.REFRESH_COOKIE);
        String edit = "{\"displayName\":\"After idle\"}";

        // A valid access cookie must not bypass CSRF or commit the rejected edit.
        mockMvc.perform(put("/api/account/profile").cookie(access).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isForbidden()).andExpect(header().string("X-CSRF-Error", "1"));
        mockMvc.perform(get("/api/auth/me").cookie(access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Before idle"));
        mockMvc.perform(post("/api/admin/users/1/revoke-sessions").cookie(access, csrfCookie)
                        .header(csrfHeader, csrfToken).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("X-CSRF-Error"));

        // Simulate the browser expiring access, and a CSRF cookie changed by another tab.
        var freshCsrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrfCookie = freshCsrf.getCookie("XSRF-TOKEN");
        mockMvc.perform(put("/api/account/profile").cookie(csrfCookie).header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isForbidden()).andExpect(header().string("X-CSRF-Error", "1"));
        var reloadedCsrf = mockMvc.perform(get("/api/auth/csrf").cookie(csrfCookie))
                .andExpect(status().isOk()).andReturn().getResponse();
        csrfToken = com.jayway.jsonpath.JsonPath.read(reloadedCsrf.getContentAsString(), "$.token");
        mockMvc.perform(put("/api/account/profile").cookie(csrfCookie).header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isUnauthorized());

        var claims = tokens.decode(access.getValue());
        org.mockito.BDDMockito.given(tokenStore.session(claims.getClaimAsString("sid"))).willReturn(java.util.Map.of(
                "user", claims.getSubject(), "version", "0",
                "expires", Long.toString(java.time.Instant.now().plusSeconds(3600).getEpochSecond())));
        // Refresh also rejects stale CSRF before rotating the token family.
        mockMvc.perform(post("/api/auth/refresh").cookie(refresh, csrfCookie).header(csrfHeader, "stale"))
                .andExpect(status().isForbidden()).andExpect(header().string("X-CSRF-Error", "1"));
        org.mockito.BDDMockito.then(tokenStore).should(org.mockito.Mockito.never()).rotate(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        var refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(refresh, csrfCookie).header(csrfHeader, csrfToken))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        access = refreshed.getCookie(JwtTokenService.ACCESS_COOKIE);
        mockMvc.perform(put("/api/account/profile").cookie(access, csrfCookie).header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("After idle"));
        org.mockito.BDDMockito.then(tokenStore).should().rotate(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void mediaUsesRealJwtCookieAndAnonymousRequestsCannotObtainS3Links() throws Exception {
        String mediaUrl = "/media/s3/2ac5e402-a6df-480f-a741-5f3f851876cf.mp3";
        mockMvc.perform(get(mediaUrl)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
        var registration = mockMvc.perform(post("/api/auth/register").with(browserCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Audio learner","email":"media-jwt@dictalab.local","password":"listen123"}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var access = registration.getCookie(JwtTokenService.ACCESS_COOKIE);
        // This test runs in local mode: reaching 503 proves the JWT cookie passed security.
        mockMvc.perform(get(mediaUrl).cookie(access)).andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Location"));
        mockMvc.perform(get("/media/s3/not-a-managed-audio.json").cookie(access))
                .andExpect(status().isNotFound()).andExpect(header().doesNotExist("Location"));
        // Existing disk audio keeps working with the same real JWT cookie, including range requests.
        var legacy = java.nio.file.Files.createTempFile(java.nio.file.Path.of("target/test-media"), "jwt-audio-", ".mp3");
        try {
            java.nio.file.Files.write(legacy, new byte[]{'I', 'D', '3', 4});
            String legacyUrl = "/media/" + legacy.getFileName();
            mockMvc.perform(get(legacyUrl)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(legacyUrl).cookie(access).header("Range", "bytes=0-2"))
                    .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range", "bytes 0-2/4"));
        } finally {
            java.nio.file.Files.deleteIfExists(legacy);
        }
        org.mockito.BDDMockito.given(tokenStore.active(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong())).willReturn(false);
        mockMvc.perform(get(mediaUrl).cookie(access)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void unauthenticatedUserCannotReadPersonalHistory() throws Exception {
        mockMvc.perform(get("/api/v1/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Vui lòng đăng nhập để tiếp tục."));
    }

    @Test
    void registerCreatesJwtCookiesAndPrivateDashboardWithoutHttpSession() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/auth/register")
                        .with(browserCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Người học thử",
                                  "email": "new-user@dictalab.local",
                                  "password": "listen123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Người học thử"))
                .andExpect(jsonPath("$.email").value("new-user@dictalab.local"))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(registration.getRequest().getSession(false)).isNull();
        var access = registration.getResponse().getCookie(JwtTokenService.ACCESS_COOKIE);
        org.assertj.core.api.Assertions.assertThat(access).isNotNull();
        org.assertj.core.api.Assertions.assertThat(access.isHttpOnly()).isTrue();
        org.assertj.core.api.Assertions.assertThat(registration.getResponse().getCookie(JwtTokenService.REFRESH_COOKIE).getPath())
                .isEqualTo("/api/auth");
        mockMvc.perform(get("/api/v1/dashboard").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.completedLessons").value(0));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(browserCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "missing@dictalab.local",
                                  "password": "wrong123"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email hoặc mật khẩu không đúng."));
    }

    @Test
    void registeringAllowlistedEmailDoesNotGrantAdminWithoutVerification() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(browserCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Content admin",
                                  "email": "admin@dictalab.local",
                                  "password": "admin1234"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));
    }
}
