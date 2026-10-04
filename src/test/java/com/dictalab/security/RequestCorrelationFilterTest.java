package com.dictalab.security;

import com.dictalab.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

class RequestCorrelationFilterTest {
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        MDC.remove("requestId");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseFailureDuringAuthenticationFailsClosed(boolean transaction) throws Exception {
        var users = mock(AppUserRepository.class);
        RuntimeException failure = transaction
                ? new CannotCreateTransactionException("private-db-detail")
                : new CannotGetJdbcConnectionException("private-db-detail");
        given(users.findById(7L)).willThrow(failure);
        var principal = new DictaLabPrincipal(7L, "test@example.invalid", "Test", "unused", "USER", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "test-503");
        var response = new MockHttpServletResponse();
        MDC.put("requestId", "previous");

        new RequestCorrelationFilter().doFilter(request, response, (req, res) ->
                new AccountStateFilter(users).doFilter(req, res, (ignoredReq, ignoredRes) ->
                        fail("Protected handler must not run when account validation fails")));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("test-503");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getContentAsString()).contains("\"status\":503", "\"errorId\":", "Hệ thống đang bận")
                .doesNotContain("private-db-detail");
        assertThat(MDC.get("requestId")).isEqualTo("previous");
    }

    @Test
    void unavailableRedisAuthenticationReturns503InsteadOfExpiredLogin() throws Exception {
        var response = new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(new MockHttpServletRequest(), response,
                (request, result) -> { throw new RedisTokenStore.Unavailable(); });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentAsString()).doesNotContain("redis://", "Authentication store unavailable");
    }

    @Test
    void committedResponseIsNotRewritten() {
        var response = new MockHttpServletResponse();
        var failure = new CannotGetJdbcConnectionException("private-db-detail");
        assertThatThrownBy(() -> new RequestCorrelationFilter().doFilter(new MockHttpServletRequest(), response,
                (req, res) -> { res.getWriter().write("already sent"); res.flushBuffer(); throw failure; }))
                .isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Retry-After")).isNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void unrelatedFailureIsNotSwallowed() {
        var failure = new IllegalStateException("unrelated");
        assertThatThrownBy(() -> new RequestCorrelationFilter().doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (req, res) -> { throw failure; })).isSameAs(failure);
        assertThat(MDC.get("requestId")).isNull();
    }
}
