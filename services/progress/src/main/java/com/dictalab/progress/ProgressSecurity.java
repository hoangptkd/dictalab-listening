package com.dictalab.progress;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration(proxyBeanMethods=false)
public class ProgressSecurity {
    @Bean
    org.springframework.security.core.userdetails.UserDetailsService internalOnlyUsers() {
        return username -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Interactive login is unavailable"); };
    }

    @Bean
    SecurityFilterChain internalApi(HttpSecurity http,
            @Value("${dictalab.progress.service-token}") String token) throws Exception {
        if (token.length() < 32 || token.length() > 256 || !token.chars().allMatch(c -> c >= 33 && c <= 126))
            throw new IllegalArgumentException("Progress service token must contain 32-256 printable ASCII characters");
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/internal/v1/**", "/actuator/prometheus").hasAuthority("SERVICE_CORE")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, failure) -> response.sendError(401)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws IOException, ServletException {
                        String supplied = request.getHeader("X-Service-Token");
                        if (supplied != null && supplied.length() <= 256 && MessageDigest.isEqual(expected,
                                supplied.getBytes(StandardCharsets.UTF_8))) {
                            var context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(new UsernamePasswordAuthenticationToken("core", null,
                                    AuthorityUtils.createAuthorityList("SERVICE_CORE")));
                            SecurityContextHolder.setContext(context);
                        }
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class);
        // Only GET routes exist. Keep CSRF protection enabled for any future unsafe routes.
        return http.build();
    }
}
