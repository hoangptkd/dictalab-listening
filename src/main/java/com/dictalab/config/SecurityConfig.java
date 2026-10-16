package com.dictalab.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import com.dictalab.security.JwtTokenService;
import com.dictalab.security.AccountStateFilter;
import com.dictalab.security.RequestRateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.config.ObjectPostProcessor;

@Configuration
public class SecurityConfig {
    // A restored snapshot must not dispatch mail/jobs or reclaim leases while being verified.
    @Configuration(proxyBeanMethods = false)
    @Profile("!restore-smoke")
    @EnableScheduling
    static class BackgroundScheduling {}

    @Bean
    public FilterRegistrationBean<AccountStateFilter> accountStateFilterRegistration(AccountStateFilter filter) {
        FilterRegistrationBean<AccountStateFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean public FilterRegistrationBean<RequestRateLimitFilter> rateLimitRegistration(RequestRateLimitFilter filter){
        var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        // JWT authentication is saved only for this request, not treated as a new login by session management.
        return new RequestAttributeSecurityContextRepository();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(
            @org.springframework.beans.factory.annotation.Value("${dictalab.auth.cookie-secure:true}") boolean secure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(secure).sameSite("Lax"));
        return repository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository contextRepository,
                                                   CookieCsrfTokenRepository csrfRepository,
                                                   AccountStateFilter accountStateFilter,
                                                   RequestRateLimitFilter rateLimitFilter,
                                                   JwtTokenService tokens,
                                                   @org.springframework.beans.factory.annotation.Value("${management.server.port:18183}") int managementPort) throws Exception {
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style; frame-src https://accounts.google.com/gsi/; img-src 'self' data:; font-src 'self'; connect-src 'self' https: wss:; media-src 'self' https: blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"))
                        .referrerPolicy(policy -> policy.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                "Cross-Origin-Opener-Policy", "same-origin-allow-popups"))
                        .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(self), geolocation=()")))
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(request -> {
                            String path = request.getRequestURI().substring(request.getContextPath().length());
                            if (path.equals("/api/billing/payos/webhook")) return null;
                            // Public auth endpoints must work when an expired access cookie is still present.
                            if (path.startsWith("/api/auth/") && !path.equals("/api/auth/me")) return null;
                            if (!path.startsWith("/api/") && !path.startsWith("/admin")
                                    && !path.startsWith("/media/")
                                    && !path.startsWith("/private-media/") && !path.startsWith("/ws/")) return null;
                            try { return JwtTokenService.cookie(request, JwtTokenService.ACCESS_COOKIE); }
                            catch (org.springframework.web.server.ResponseStatusException invalid) {
                                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token");
                            }
                        })
                        .jwt(jwt -> jwt.decoder(tokens::decode).jwtAuthenticationConverter(decoded -> {
                            try { return (org.springframework.security.authentication.AbstractAuthenticationToken) tokens.authentication(decoded); }
                            catch (org.springframework.web.server.ResponseStatusException invalid) {
                                throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token");
                            }
                        }))
                        .authenticationEntryPoint((request, response, exception) -> authenticationRequired(request, response)))
                .addFilterAfter(accountStateFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, AccountStateFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override public <O extends CsrfFilter> O postProcess(O filter) {
                                // Cookie JWTs must not inherit Resource Server's bearer-token CSRF exemption.
                                filter.setRequireCsrfProtectionMatcher(request ->
                                        !request.getRequestURI().substring(request.getContextPath().length()).equals("/api/billing/payos/webhook")
                                        && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request));
                                return filter;
                            }
                        }))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,"/api/billing/payos/webhook").permitAll()
                        // Source files include answers/transcripts and must never be served by the static handler.
                        .requestMatchers(request -> {
                            String path = java.net.URLDecoder.decode(
                                    request.getRequestURI().substring(request.getContextPath().length()),
                                    java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
                            return path.startsWith("/audio/elllo/")
                                    && (path.endsWith(".json") || path.endsWith(".txt"));
                        }).denyAll()
                        // Static resources
                        .requestMatchers("/conversation-media/**").denyAll()
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/images/**", "/audio/**",
                                "/favicon.ico", "/favicon.svg", "/policies.html", "/privacy", "/terms", "/help").permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/public/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/levels", "/api/v1/catalog/lessons", "/api/v1/library").permitAll()
                        // Auth API
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/auth/google/config").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/google", "/api/auth/google/challenge",
                                "/api/auth/password-reset/request", "/api/auth/password-reset/complete",
                                "/api/auth/email/confirm", "/api/auth/email/change/confirm",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // Health check
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").access((authentication, context) -> {
                            var request=context.getRequest();
                            boolean local=java.util.Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr());
                            return new org.springframework.security.authorization.AuthorizationDecision(local
                                    && request.getLocalPort()==managementPort);
                        })
                        // SPA client-side routes — chỉ phục vụ index.html, bảo vệ do frontend
                        // Những route này cần permit để browser mở trực tiếp / tải lại được.
                        .requestMatchers(HttpMethod.GET,
                                "/login", "/register", "/reset-password", "/verify-email", "/account", "/support",
                                "/catalog",
                                "/listening", "/listening/*",
                                "/conversation", "/conversation/*",
                                "/practice/*",
                                "/history", "/history/*",
                                "/dashboard",
                                "/rooms", "/rooms/*", "/rooms/join/*"
                        ).permitAll()
                        .requestMatchers("/admin", "/admin/**", "/admin.html").hasRole("ADMIN")
                        // Private media — chỉ ADMIN
                        .requestMatchers("/private-media/**").hasRole("ADMIN")
                        // Admin API — chỉ ADMIN
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // WebSocket upgrade — xác thực qua JWT cookie, Origin check trong handler
                        .requestMatchers("/ws/room/**").authenticated()
                        .anyRequest().authenticated()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> authenticationRequired(request, response))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                // Only pre-controller CSRF rejections are safe for the client to replay.
                                response.setHeader("X-CSRF-Error", "1");
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "Thông tin bảo vệ yêu cầu đã thay đổi. Vui lòng thử lại.");
                                return;
                            }
                            if (isAdminPage(request)) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                request.getRequestDispatcher("/error/403.html").forward(request, response);
                                return;
                            }
                            writeError(
                                response, HttpServletResponse.SC_FORBIDDEN,
                                "Bạn không có quyền thực hiện thao tác này."
                            );
                        })
                )
                .build();
    }

    private void authenticationRequired(jakarta.servlet.http.HttpServletRequest request,
                                        HttpServletResponse response) throws java.io.IOException {
        if (isAdminPage(request)) {
            String next = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect("/login?next=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Vui lòng đăng nhập để tiếp tục.");
    }

    private boolean isAdminPage(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod())
                && (path.equals("/admin") || path.startsWith("/admin/") || path.equals("/admin.html"));
    }

    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
