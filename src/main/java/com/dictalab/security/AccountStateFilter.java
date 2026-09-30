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
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof DictaLabPrincipal user && users.findById(user.id())
                .filter(current -> current.isEnabled() && current.getAuthVersion() == user.authVersion()
                        && current.getEmail().equals(user.email()) && current.getRole().equals(user.role()))
                .isEmpty()) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) request.getSession(false).invalidate();
        }
        chain.doFilter(request, response);
    }
}
