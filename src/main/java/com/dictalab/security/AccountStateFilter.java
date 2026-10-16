package com.dictalab.security;

import com.dictalab.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AccountStateFilter extends OncePerRequestFilter {
    private final AppUserRepository users;

    public AccountStateFilter(AppUserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        // JWT principals already reflect the database row checked during this request.
        // Keep validation for other authentication paths, including stale supplied principals.
        if (!(authentication instanceof JwtTokenService.AccountCheckedAuthentication)
                && principal instanceof DictaLabPrincipal user && users.authenticationState(user.id())
                .filter(current -> current.isEnabled() && current.getAuthVersion() == user.authVersion()
                        && current.getEmail().equals(user.email()) && current.getRole().equals(user.role()))
                .isEmpty()) {
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
