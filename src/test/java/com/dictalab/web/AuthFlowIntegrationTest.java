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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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

    @Test
    void unauthenticatedUserCannotReadCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/levels"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Vui lòng đăng nhập để tiếp tục."));
    }

    @Test
    void registerCreatesJwtCookiesAndPrivateDashboardWithoutHttpSession() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
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
                        .with(csrf())
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
                        .with(csrf())
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
