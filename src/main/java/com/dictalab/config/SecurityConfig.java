package com.dictalab.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import com.dictalab.security.JwtTokenService;
import com.dictalab.security.AccountStateFilter;
import com.dictalab.security.RequestRateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableScheduling
public class SecurityConfig {
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
        return new NullSecurityContextRepository();
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
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(request -> {
                            String path = request.getRequestURI().substring(request.getContextPath().length());
                            // Public auth endpoints must work when an expired access cookie is still present.
                            if (path.startsWith("/api/auth/") && !path.equals("/api/auth/me")) return null;
                            if (!path.startsWith("/api/") && !path.startsWith("/admin")
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
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .authorizeHttpRequests(auth -> auth
                        // Source files include answers/transcripts and must never be served by the static handler.
                        .requestMatchers(request -> {
                            String path = java.net.URLDecoder.decode(
                                    request.getRequestURI().substring(request.getContextPath().length()),
                                    java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
                            return path.startsWith("/audio/elllo/")
                                    && (path.endsWith(".json") || path.endsWith(".txt"));
                        }).denyAll()
                        // Static resources
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/images/**", "/audio/**",
                                "/favicon.ico", "/favicon.svg").permitAll()
                        // Auth API
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
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
