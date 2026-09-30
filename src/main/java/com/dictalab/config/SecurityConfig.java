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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import com.dictalab.security.AccountStateFilter;
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
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository contextRepository,
                                                   CookieCsrfTokenRepository csrfRepository,
                                                   AccountStateFilter accountStateFilter) throws Exception {
        return http
                .addFilterAfter(accountStateFilter, SecurityContextHolderFilter.class)
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
                                "/api/auth/email/confirm", "/api/auth/email/change/confirm").permitAll()
                        // Health check
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // SPA client-side routes — chỉ phục vụ index.html, bảo vệ do frontend
                        // Những route này cần permit để browser mở trực tiếp / tải lại được.
                        .requestMatchers(HttpMethod.GET,
                                "/login", "/register", "/reset-password", "/verify-email", "/account", "/support",
                                "/catalog",
                                "/listening", "/listening/*",
                                "/practice/*",
                                "/history", "/history/*",
                                "/dashboard",
                                "/rooms", "/rooms/*", "/rooms/join/*",
                                "/admin/lessons", "/admin/lessons/new", "/admin/lessons/*"
                        ).permitAll()
                        // Private media — chỉ ADMIN
                        .requestMatchers("/private-media/**").hasRole("ADMIN")
                        // Admin API — chỉ ADMIN
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // WebSocket upgrade — xác thực qua session, Origin check trong handler
                        .requestMatchers("/ws/room/**").authenticated()
                        .anyRequest().authenticated()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, HttpServletResponse.SC_UNAUTHORIZED,
                                "Vui lòng đăng nhập để tiếp tục."
                        ))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, HttpServletResponse.SC_FORBIDDEN,
                                "Bạn không có quyền thực hiện thao tác này."
                        ))
                )
                .build();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
