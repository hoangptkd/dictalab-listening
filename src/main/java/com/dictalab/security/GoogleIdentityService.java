package com.dictalab.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/** Google establishes identity; DictaLab still issues and revokes its own sessions. */
@Service
public class GoogleIdentityService {
    static final String NONCE_COOKIE = "DICTALAB_GOOGLE_NONCE";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private final String clientId;
    private final boolean secureCookie;
    private final StringRedisTemplate redis;
    private final JwtDecoder decoder;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public GoogleIdentityService(@Value("${dictalab.auth.google-client-id:}") String clientId,
                                 @Value("${dictalab.auth.cookie-secure:true}") boolean secureCookie,
                                 StringRedisTemplate redis) {
        this(clientId.trim(), secureCookie, redis, googleDecoder(clientId.trim()));
    }

    GoogleIdentityService(String clientId, boolean secureCookie, StringRedisTemplate redis, JwtDecoder decoder) {
        this.clientId = clientId;
        this.secureCookie = secureCookie;
        this.redis = redis;
        this.decoder = decoder;
    }

    public String clientId() { return clientId; }

    public String challenge(HttpServletResponse response) {
        requireEnabled();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try { redis.opsForValue().set(key(nonce), "1", LIFETIME); }
        catch (org.springframework.dao.DataAccessException unavailable) { throw unavailable(); }
        nonceCookie(response, nonce, LIFETIME);
        return nonce;
    }

    public Identity verify(String credential, HttpServletRequest request) {
        requireEnabled();
        String nonce = JwtTokenService.cookie(request, NONCE_COOKIE);
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        Jwt token;
        try { token = decoder.decode(credential); }
        catch (JwtException | IllegalArgumentException invalidToken) { throw invalid(); }
        String subject = token.getSubject();
        String email = token.getClaimAsString("email");
        if (!nonce.equals(token.getClaimAsString("nonce")) || subject == null || subject.isBlank()
                || subject.length() > 255 || email == null || email.length() > 160
                || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || !Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) throw invalid();
        try {
            if (!"1".equals(redis.opsForValue().getAndDelete(key(nonce)))) throw invalid();
        } catch (org.springframework.dao.DataAccessException unavailable) { throw unavailable(); }
        email = email.toLowerCase(Locale.ROOT);
        String hostedDomain = token.getClaimAsString("hd");
        boolean authoritative = email.endsWith("@gmail.com") || (hostedDomain != null && !hostedDomain.isBlank());
        String name = token.getClaimAsString("name");
        if (name == null || name.isBlank()) name = email.substring(0, email.indexOf('@'));
        name = name.strip();
        if (name.length() < 2) name = "Người học";
        if (name.length() > 80) name = name.substring(0, 80);
        return new Identity(subject, email, name, authoritative);
    }

    public void clearChallenge(HttpServletResponse response) { nonceCookie(response, "", Duration.ZERO); }

    private void nonceCookie(HttpServletResponse response, String value, Duration lifetime) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NONCE_COOKIE, value)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/api/auth/google")
                .maxAge(lifetime).build().toString());
    }

    private void requireEnabled() {
        if (clientId.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Đăng nhập Google chưa được cấu hình. Vui lòng dùng email và mật khẩu.");
    }
    private String key(String nonce) { return "{dictalab-auth}:google:" + nonce; }
    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Đăng nhập Google không hợp lệ hoặc đã hết hạn. Vui lòng thử lại.");
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Đăng nhập tạm thời không khả dụng. Vui lòng thử lại sau.");
    }

    static NimbusJwtDecoder googleDecoder(String clientId) {
        var http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(5000);
        http.setReadTimeout(5000);
        var decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .restOperations(new RestTemplate(http)).build();
        decoder.setJwtValidator(validator(clientId));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String clientId) {
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO), token -> {
            String issuer = token.getClaimAsString("iss");
            Instant now = Instant.now();
            boolean valid = ("https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer))
                    && token.getAudience().contains(clientId) && !clientId.isBlank()
                    && token.getExpiresAt() != null && token.getIssuedAt() != null
                    && token.getIssuedAt().isBefore(now.plusSeconds(60))
                    && token.getExpiresAt().isAfter(token.getIssuedAt())
                    && (token.getClaimAsString("azp") == null || clientId.equals(token.getClaimAsString("azp")));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid Google identity claims", null));
        });
    }

    public record Identity(String subject, String email, String displayName, boolean authoritativeEmail) {}
}
