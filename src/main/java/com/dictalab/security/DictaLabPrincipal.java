package com.dictalab.security;

import com.dictalab.domain.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public record DictaLabPrincipal(
        Long id,
        String email,
        String displayName,
        String passwordHash,
        String role,
        boolean enabled,
        long authVersion,
        String tokenSessionId,
        java.time.Instant tokenExpiresAt
) implements UserDetails {

    public DictaLabPrincipal(Long id, String email, String displayName, String passwordHash,
                             String role, boolean enabled, long authVersion) {
        this(id, email, displayName, passwordHash, role, enabled, authVersion, null, null);
    }

    public DictaLabPrincipal(Long id, String email, String displayName, String passwordHash,
                             String role, boolean enabled) {
        this(id, email, displayName, passwordHash, role, enabled, 0);
    }

    public static DictaLabPrincipal from(AppUser user) {
        return new DictaLabPrincipal(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash(),
                user.getRole(), user.isEnabled(), user.getAuthVersion()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() { return enabled; }
}
