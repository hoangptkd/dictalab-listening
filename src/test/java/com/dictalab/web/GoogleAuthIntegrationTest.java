package com.dictalab.web;

import com.dictalab.security.GoogleIdentityService;
import com.dictalab.security.RedisTokenStore;
import com.dictalab.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
class GoogleAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean GoogleIdentityService google;
    @MockitoBean RedisTokenStore store;

    @BeforeEach void configure() {
        when(google.clientId()).thenReturn("");
        when(store.active(anyString(), anyLong(), anyLong())).thenReturn(true);
    }
    RequestPostProcessor browserCsrf() throws Exception {
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String header = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.headerName");
        String token = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.token");
        return request -> {request.setCookies(response.getCookie("XSRF-TOKEN")); request.addHeader(header, token); return request;};
    }
    @Test void configurationIsPublicAndNeverCached() throws Exception {
        mvc.perform(get("/api/auth/google/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.clientId").value(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        when(google.clientId()).thenReturn("test.apps.googleusercontent.com");
        mvc.perform(get("/api/auth/google/config")).andExpect(jsonPath("$.enabled").value(true));
    }
    @Test void googleLoginAndChallengeRequireBrowserCsrfBeforeProcessingTokens() throws Exception {
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"test\"}"))
                .andExpect(status().isForbidden()).andExpect(header().string("X-CSRF-Error", "1"));
        mvc.perform(post("/api/auth/google/challenge")).andExpect(status().isForbidden());
        verify(google, never()).verify(anyString(), any());
        verify(google, never()).challenge(any());
    }
    @Test void verifiedGoogleIdentityUsesExistingJwtCookiesAndAuthenticatedAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        when(google.verify(eq("test-credential"), any())).thenReturn(
                new GoogleIdentityService.Identity(subject, subject + "@gmail.com", "Google Learner", true));
        var result = mvc.perform(post("/api/auth/google").with(browserCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"test-credential\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.role").value("USER")).andReturn();
        var response = result.getResponse();
        assertThat(response.getCookie(JwtTokenService.ACCESS_COOKIE)).isNotNull();
        assertThat(response.getCookie(JwtTokenService.REFRESH_COOKIE)).isNotNull();
        assertThat(response.getCookie(JwtTokenService.ACCESS_COOKIE).isHttpOnly()).isTrue();
        assertThat(result.getRequest().getSession(false)).isNull();
        verify(google).clearChallenge(any());
        mvc.perform(get("/api/auth/me").cookie(response.getCookie(JwtTokenService.ACCESS_COOKIE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(subject + "@gmail.com"));
    }
    @Test void invalidIdentityNeverIssuesApplicationCookies() throws Exception {
        when(google.verify(anyString(), any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid Google identity"));
        var result = mvc.perform(post("/api/auth/google").with(browserCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"invalid\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getCookie(JwtTokenService.ACCESS_COOKIE)).isNull();
        verify(store, never()).create(anyString(), anyLong(), anyLong(), anyString(), any());
    }
}
