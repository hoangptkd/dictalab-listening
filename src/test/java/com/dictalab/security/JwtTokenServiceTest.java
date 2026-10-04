package com.dictalab.security;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

class JwtTokenServiceTest {
    private final RedisTokenStore store = mock(RedisTokenStore.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final String firstKey = Base64.getEncoder().encodeToString("first-test-only-key-".repeat(4).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private final String nextKey = Base64.getEncoder().encodeToString("second-test-only-key-".repeat(4).getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private JwtTokenService service(String key, String previous, String kid, String previousKid, String issuer) {
        return new JwtTokenService(store, users, key, previous, kid, previousKid, issuer,
                Duration.ofMinutes(10), Duration.ofDays(7), true);
    }

    @Test void strongConfiguredKeyIsRequiredAndIssuerAndAudienceAreValidated() {
        assertThatThrownBy(() -> service("", "", "1", "0", "dictalab")).isInstanceOf(IllegalArgumentException.class);
        var user = mock(AppUser.class); given(user.getId()).willReturn(42L);
        var response = new MockHttpServletResponse(); service(firstKey, "", "1", "0", "another-issuer").issue(user, response);
        assertThatThrownBy(() -> service(firstKey, "", "1", "0", "dictalab")
                .decode(response.getCookie(JwtTokenService.ACCESS_COOKIE).getValue())).isInstanceOf(JwtException.class);
        var encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(
                        new com.nimbusds.jose.jwk.OctetSequenceKey.Builder(Base64.getDecoder().decode(firstKey))
                                .keyID("1").algorithm(com.nimbusds.jose.JWSAlgorithm.HS256).build())));
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("dictalab")
                .subject("42").audience(java.util.List.of("other-app")).claim("type", "access")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        String wrongAudience = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                        .keyId("1").build(), claims)).getTokenValue();
        assertThatThrownBy(() -> service(firstKey, "", "1", "0", "dictalab").decode(wrongAudience)).isInstanceOf(JwtException.class);
    }

    @Test void rollingKeyRotationAcceptsPreviousKeyThenRetirementRejectsIt() {
        var user = mock(AppUser.class); given(user.getId()).willReturn(42L);
        given(user.isEnabled()).willReturn(true); given(user.getAuthVersion()).willReturn(3L);
        given(user.getRole()).willReturn("USER"); given(users.findById(42L)).willReturn(Optional.of(user));
        given(store.active(anyString(), eq(42L), eq(3L))).willReturn(true);
        var oldResponse = new MockHttpServletResponse(); service(firstKey, "", "old", "unused", "dictalab").issue(user, oldResponse);
        String oldToken = oldResponse.getCookie(JwtTokenService.ACCESS_COOKIE).getValue();
        assertThat(oldResponse.getCookie(JwtTokenService.ACCESS_COOKIE).getSecure()).isTrue();
        var rolling = service(nextKey, firstKey, "new", "old", "dictalab");
        var decoded = rolling.decode(oldToken);
        var principal = (DictaLabPrincipal)rolling.authentication(decoded).getPrincipal();
        assertThat(principal.passwordHash()).isEmpty(); assertThat(principal.tokenSessionId()).isNotBlank();
        assertThat(principal.tokenExpiresAt()).isAfter(Instant.now());
        assertThatThrownBy(() -> service(nextKey, "", "new", "old", "dictalab").decode(oldToken)).isInstanceOf(JwtException.class);
    }

    @Test void storeFailureIsSanitizedAndDoesNotIssueCookies() {
        var user = mock(AppUser.class); given(user.getId()).willReturn(42L);
        willThrow(new RedisTokenStore.Unavailable()).given(store).create(anyString(), anyLong(), anyLong(), anyString(), any());
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service(firstKey, "", "1", "0", "dictalab").issue(user, response))
                .isInstanceOf(RedisTokenStore.Unavailable.class);
        assertThat(response.getCookies()).isEmpty();
    }
}
