package com.dictalab.service;

import com.dictalab.domain.AppUser;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.dictalab.security.DictaLabPrincipal;

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
    private final TransactionTemplate transaction;

    public AccountService(AppUserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager, PlatformTransactionManager manager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.clock = Clock.systemUTC();
        this.transaction = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AuthenticatedAccount register(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email này đã được đăng ký.");
        }
        String encoded = passwordEncoder.encode(request.password());
        try {
            return transaction.execute(status -> {
                Instant now = Instant.now(clock);
                AppUser user = new AppUser(email, request.displayName().trim(), encoded, now);
                user.recordLogin(now);
                user = userRepository.saveAndFlush(user);
                DictaLabPrincipal principal = DictaLabPrincipal.from(user);
                return new AuthenticatedAccount(user, UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
            });
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            throw new EmailAlreadyExistsException("Email đã được sử dụng.");
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AuthenticatedAccount login(AuthDtos.LoginRequest request) {
        String email = normalizeEmail(request.email());
        Authentication authentication = authenticate(email, request.password());
        DictaLabPrincipal verified = (DictaLabPrincipal) authentication.getPrincipal();
        return transaction.execute(status -> {
            AppUser user = userRepository.lockById(verified.id())
                    .orElseThrow(() -> new AuthenticationFailedException("Email hoặc mật khẩu không đúng."));
            if (!user.isEnabled() || user.getAuthVersion() != verified.authVersion()
                    || !user.getEmail().equals(verified.email()) || !user.getPasswordHash().equals(verified.passwordHash()))
                throw new AuthenticationFailedException("Tài khoản đã thay đổi. Vui lòng đăng nhập lại.");
            user.recordLogin(Instant.now(clock));
            DictaLabPrincipal current = DictaLabPrincipal.from(user);
            return new AuthenticatedAccount(user, UsernamePasswordAuthenticationToken.authenticated(
                    current, null, current.getAuthorities()));
        });
    }

    public AppUser requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy tài khoản."));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AuthenticatedAccount loginGoogle(com.dictalab.security.GoogleIdentityService.Identity identity) {
        // Hash outside the database transaction, just like password registration.
        String unusablePassword = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        try {
            return transaction.execute(status -> authenticateGoogle(identity, unusablePassword));
        } catch (org.springframework.dao.DataIntegrityViolationException concurrentSignup) {
            // A simultaneous first login may have committed the unique subject/email already.
            return transaction.execute(status -> authenticateGoogle(identity, unusablePassword));
        }
    }

    private AuthenticatedAccount authenticateGoogle(com.dictalab.security.GoogleIdentityService.Identity identity,
                                                     String unusablePassword) {
        AppUser found = userRepository.findByGoogleSubject(identity.subject())
                .orElseGet(() -> userRepository.findByEmail(identity.email()).orElse(null));
        AppUser user = found == null ? null : userRepository.lockById(found.getId()).orElseThrow();
        Instant now = Instant.now(clock);
        if (user == null) {
            user = new AppUser(identity.email(), identity.displayName(), unusablePassword, now);
            user.linkGoogle(identity.subject(), now);
            if (identity.authoritativeEmail()) user.verifyEmail(now);
            user = userRepository.saveAndFlush(user);
        } else {
            if (!user.isEnabled()) throw new AuthenticationFailedException("Tài khoản đã bị khóa.");
            if (user.getGoogleSubject() == null) {
                // Only Gmail/Workspace can prove current ownership of an existing email.
                if (!identity.authoritativeEmail() || !user.getEmail().equals(identity.email()))
                    throw new AuthenticationFailedException("Email này đã có tài khoản. Vui lòng đăng nhập bằng mật khẩu.");
                // Do not attach Google to an unverified password account created by someone else.
                if (!user.isEmailVerified()) throw new AuthenticationFailedException(
                        "Tài khoản email hiện có chưa được xác minh. Hãy đăng nhập bằng mật khẩu và xác minh email; nếu cần, dùng Quên mật khẩu.");
                user.linkGoogle(identity.subject(), now);
                user.verifyEmail(now);
            } else if (!user.getGoogleSubject().equals(identity.subject())) {
                throw new AuthenticationFailedException("Tài khoản đã liên kết với một tài khoản Google khác.");
            }
        }
        user.recordLogin(now);
        DictaLabPrincipal principal = DictaLabPrincipal.from(user);
        return new AuthenticatedAccount(user, UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
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
