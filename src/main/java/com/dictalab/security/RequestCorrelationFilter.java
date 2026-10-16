package com.dictalab.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+10)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String supplied=request.getHeader("X-Request-ID");
        String id=supplied!=null && supplied.matches("[A-Za-z0-9_.-]{1,64}")?supplied:UUID.randomUUID().toString();
        response.setHeader("X-Request-ID",id);
        String previous=MDC.get("requestId");
        MDC.put("requestId",id);
        try {
            chain.doFilter(request,response);
        } catch (CannotCreateTransactionException | CannotGetJdbcConnectionException | RedisTokenStore.Unavailable failure) {
            // Authentication can access the database before MVC exception handlers run. Fail closed.
            if (response.isCommitted()) throw failure;
            String errorId = UUID.randomUUID().toString();
            log.warn("Authentication infrastructure unavailable, errorId={}, errorType={}", errorId, failure.getClass().getSimpleName());
            response.resetBuffer();
            response.setStatus(503);
            response.setHeader("Retry-After", "3");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"status\":503,\"message\":\"Hệ thống đang bận. Vui lòng chờ và thử lại.\",\"errorId\":\"" + errorId + "\"}");
        } finally {
            if(previous==null)MDC.remove("requestId");else MDC.put("requestId",previous);
        }
    }
}
