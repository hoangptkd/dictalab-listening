package com.dictalab.security;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.JWSAlgorithm;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class JwtTokenService {
    public static final String ACCESS_COOKIE = "DICTALAB_ACCESS";
    public static final String REFRESH_COOKIE = "DICTALAB_REFRESH";
    private final RedisTokenStore sessions;
    private final AppUserRepository users;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final String keyId;
    private final Duration accessLifetime;
    private final Duration refreshLifetime;
    private final boolean secure;
    private final io.micrometer.core.instrument.Timer accountRead;
    private final io.micrometer.core.instrument.Timer sessionCheck;
    private final SecureRandom random = new SecureRandom();

    public JwtTokenService(RedisTokenStore sessions, AppUserRepository users,
            @Value("${dictalab.auth.signing-key:}") String signingKey,
            @Value("${dictalab.auth.previous-signing-key:}") String previousKey,
            @Value("${dictalab.auth.key-id:1}") String keyId,
            @Value("${dictalab.auth.previous-key-id:0}") String previousKeyId,
            @Value("${dictalab.auth.issuer:dictalab}") String issuer,
            @Value("${dictalab.auth.access-lifetime:10m}") Duration accessLifetime,
            @Value("${dictalab.auth.refresh-lifetime:7d}") Duration refreshLifetime,
            @Value("${dictalab.auth.cookie-secure:true}") boolean secure,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        if (accessLifetime.isNegative() || accessLifetime.isZero() || accessLifetime.compareTo(Duration.ofHours(1)) > 0
                || refreshLifetime.compareTo(accessLifetime) <= 0 || refreshLifetime.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("Invalid token lifetimes");
        this.sessions = sessions; this.users = users; this.issuer = issuer; this.keyId = keyId;
        this.accessLifetime = accessLifetime; this.refreshLifetime = refreshLifetime; this.secure = secure;
        accountRead = metrics.timer("dictalab.auth.lookup", "store", "postgres");
        sessionCheck = metrics.timer("dictalab.auth.lookup", "store", "redis");
        byte[] secret = secret(signingKey);
        if (issuer.isBlank() || keyId.isBlank() || !previousKey.isBlank() && keyId.equals(previousKeyId))
            throw new IllegalArgumentException("Invalid JWT issuer or key IDs");
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
                new OctetSequenceKey.Builder(secret).keyID(keyId).algorithm(JWSAlgorithm.HS256).build())));
        JwtDecoder current = decoder(secret, issuer, keyId);
        JwtDecoder previous = previousKey.isBlank() ? null : decoder(secret(previousKey), issuer, previousKeyId);
        decoder = token -> {
            try { return current.decode(token); }
            catch (JwtException invalid) { if (previous == null) throw invalid; return previous.decode(token); }
        };
    }

    private static byte[] secret(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < 32) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("JWT_SIGNING_KEY must be a Base64 secret with at least 32 random bytes");
        }
    }

    private static JwtDecoder decoder(byte[] secret, String issuer, String kid) {
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains("dictalab-web")),
                jwt -> kid.equals(jwt.getHeaders().get("kid")) && "access".equals(jwt.getClaimAsString("type"))
                        && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null && !jwt.getIssuedAt().isAfter(Instant.now())
                    ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                    : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                        new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"))));
        return decoder;
    }

    public Jwt decode(String access) { return decoder.decode(access); }

    public Authentication authentication(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            long version = ((Number) jwt.getClaim("ver")).longValue();
            String sid = canonicalSid(jwt.getClaimAsString("sid"));
            if (!sessionCheck.record(() -> sessions.active(sid, userId, version))) throw unauthorized();
            var user = currentUser(userId, version);
            var principal = new DictaLabPrincipal(userId, user.getEmail(), user.getDisplayName(), "",
                    user.getRole(), true, version, sid, jwt.getExpiresAt());
            return new AccountCheckedAuthentication(principal);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) {
            throw unauthorized();
        }
    }

    // Only the JWT converter can create this token, after Redis and current account checks.
    // SecurityConfig stores it in RequestAttributeSecurityContextRepository, never in an HTTP session.
    static final class AccountCheckedAuthentication extends UsernamePasswordAuthenticationToken {
        private AccountCheckedAuthentication(DictaLabPrincipal principal) {
            super(principal, null, principal.getAuthorities());
        }
    }

    public void issue(AppUser user, HttpServletResponse response) {
        String sid = UUID.randomUUID().toString();
        Instant expires = Instant.now().plus(refreshLifetime);
        String refresh = sid + "." + randomSecret();
        String access = access(user.getId(), user.getAuthVersion(), sid);
        sessions.create(sid, user.getId(), user.getAuthVersion(), hash(refresh), expires);
        cookies(response, access, refresh, expires);
    }

    public void issue(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        // Repeated login in the same browser replaces its old token family instead of leaking a slot.
        revokePresentedRefresh(request);
        issue(user, response);
    }

    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        String refresh = cookie(request, REFRESH_COOKIE);
        String sid = refreshSid(refresh);
        var saved = sessions.session(sid);
        if (saved.isEmpty()) throw unauthorized();
        var user = currentUser(Long.parseLong((String)saved.get("user")), Long.parseLong((String)saved.get("version")));
        Instant expires = Instant.ofEpochSecond(Long.parseLong((String)saved.get("expires")));
        if (!expires.isAfter(Instant.now())) throw unauthorized();
        String next = sid + "." + randomSecret();
        String access = access(user.getId(), user.getAuthVersion(), sid);
        sessions.rotate(sid, user.getId(), user.getAuthVersion(), hash(refresh), hash(next));
        cookies(response, access, next, expires);
    }

    /** Logout is available even after the access cookie has expired; require proof of the refresh secret. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        revokePresentedRefresh(request);
        clearCookies(response);
    }

    private void revokePresentedRefresh(HttpServletRequest request) {
        String token = cookie(request, REFRESH_COOKIE);
        if (token != null) {
            String sid;
            try { sid = refreshSid(token); } catch (ResponseStatusException invalid) { return; }
            if (sessions.recognizes(sid, hash(token))) sessions.revoke(sid);
        }
    }

    public boolean active(DictaLabPrincipal user) {
        return user.tokenSessionId() != null && user.tokenExpiresAt() != null && user.tokenExpiresAt().isAfter(Instant.now())
                && sessions.active(user.tokenSessionId(), user.id(), user.authVersion());
    }

    private AppUserRepository.AuthenticationState currentUser(long id, long version) {
        return accountRead.record(() -> users.authenticationState(id)).filter(user -> user.isEnabled() && user.getAuthVersion() == version)
                .orElseThrow(JwtTokenService::unauthorized);
    }

    private String access(long userId, long version, String sid) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of("dictalab-web"))
                .subject(Long.toString(userId)).issuedAt(now).expiresAt(now.plus(accessLifetime))
                .id(UUID.randomUUID().toString()).claim("sid", sid).claim("ver", version).claim("type", "access").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).keyId(keyId).build(), claims)).getTokenValue();
    }

    private void cookies(HttpServletResponse response, String access, String refresh, Instant expires) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        setCookie(response, ACCESS_COOKIE, access, "/", accessLifetime);
        setCookie(response, REFRESH_COOKIE, refresh, "/api/auth", Duration.between(Instant.now(), expires));
    }

    public void clearCookies(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        setCookie(response, ACCESS_COOKIE, "", "/", Duration.ZERO);
        setCookie(response, REFRESH_COOKIE, "", "/api/auth", Duration.ZERO);
        setCookie(response, "JSESSIONID", "", "/", Duration.ZERO);
    }

    private void setCookie(HttpServletResponse response, String name, String value, String path, Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).httpOnly(true).secure(secure)
                .sameSite("Lax").path(path).maxAge(age).build().toString());
    }

    public static String cookie(HttpServletRequest request, String name) {
        String value = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) {
            if (value != null) throw unauthorized();
            value = cookie.getValue();
        }
        return value;
    }

    private static String canonicalSid(String sid) {
        if (sid == null || !UUID.fromString(sid).toString().equals(sid)) throw new IllegalArgumentException("Invalid session ID");
        return sid;
    }

    private static String refreshSid(String token) {
        if (token == null || !token.matches("[a-f0-9-]{36}\\.[A-Za-z0-9_-]{43}")) throw unauthorized();
        try { return canonicalSid(token.substring(0, 36)); } catch (IllegalArgumentException invalid) { throw unauthorized(); }
    }

    private String randomSecret() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn hoặc không hợp lệ.");
    }
}
