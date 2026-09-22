package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.config.AdminProperties;
import com.dictalab.dto.AuthDtos;
import com.dictalab.exception.AuthenticationFailedException;
import com.dictalab.exception.EmailAlreadyExistsException;
import com.dictalab.repository.AppUserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final Clock clock;
    private final AdminProperties adminProperties;

    public AccountService(AppUserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager,
                          AdminProperties adminProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.adminProperties = adminProperties;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public AuthenticatedAccount register(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email này đã được đăng ký.");
        }
        Instant now = Instant.now(clock);
        AppUser user = new AppUser(
                email,
                request.displayName().trim(),
                passwordEncoder.encode(request.password()),
                now
        );
        if (adminProperties.contains(email)) {
            user.promoteToAdmin(now);
        }
        user = userRepository.save(user);
        Authentication authentication = authenticate(email, request.password());
        user.recordLogin(now);
        return new AuthenticatedAccount(user, authentication);
    }

    @Transactional
    public AuthenticatedAccount login(AuthDtos.LoginRequest request) {
        String email = normalizeEmail(request.email());
        Instant now = Instant.now(clock);
        Authentication authentication = authenticate(email, request.password());
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationFailedException("Email hoặc mật khẩu không đúng."));
        if (adminProperties.contains(email) && user.promoteToAdmin(now)) {
            userRepository.saveAndFlush(user);
            authentication = authenticate(email, request.password());
        }
        user.recordLogin(now);
        return new AuthenticatedAccount(user, authentication);
    }

    public AppUser requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản."));
    }

    private Authentication authenticate(String email, String password) {
        try {
            return authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password)
            );
        } catch (BadCredentialsException | UsernameNotFoundException exception) {
            throw new AuthenticationFailedException("Email hoặc mật khẩu không đúng.");
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthenticatedAccount(AppUser user, Authentication authentication) {
    }
}
