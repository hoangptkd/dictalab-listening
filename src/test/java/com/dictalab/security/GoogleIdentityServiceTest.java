package com.dictalab.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleIdentityServiceTest {
    static final String CLIENT = "test-client.apps.googleusercontent.com";
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    GoogleIdentityService service;
    KeyPair keys;
    String nonce;
    MockHttpServletRequest request;

    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(GoogleIdentityService.validator(CLIENT));
        service = new GoogleIdentityService(CLIENT, true, redis, decoder);
        when(redis.opsForValue()).thenReturn(values);
        nonce = service.challenge(new MockHttpServletResponse());
        request = new MockHttpServletRequest();
        request.setCookies(new Cookie(GoogleIdentityService.NONCE_COOKIE, nonce));
        when(values.getAndDelete(anyString())).thenReturn("1", null);
    }

    JWTClaimsSet.Builder claims() {
        var now = Instant.now();
        return new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(CLIENT)
                .subject("google-subject-123").issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(300))).claim("nonce", nonce)
                .claim("email", "learner@gmail.com").claim("email_verified", true).claim("name", "Người học");
    }
    String signed(JWTClaimsSet.Builder claims, KeyPair pair) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
        return jwt.serialize();
    }
    void rejected(JWTClaimsSet.Builder claims) throws Exception {
        String credential = signed(claims, keys);
        assertThatThrownBy(() -> service.verify(credential, request))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
        verify(values, never()).getAndDelete(anyString());
    }

    @Test void verifiesSignedIdentityAndConsumesChallengeExactlyOnce() throws Exception {
        String credential = signed(claims(), keys);
        var identity = service.verify(credential, request);
        assertThat(identity.subject()).isEqualTo("google-subject-123");
        assertThat(identity.authoritativeEmail()).isTrue();
        assertThatThrownBy(() -> service.verify(credential, request)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsWrongAudience() throws Exception { rejected(claims().audience("another-app")); }
    @Test void rejectsWrongIssuer() throws Exception { rejected(claims().issuer("https://attacker.test")); }
    @Test void rejectsExpiredToken() throws Exception { rejected(claims().expirationTime(Date.from(Instant.now().minusSeconds(1)))); }
    @Test void rejectsUnverifiedEmail() throws Exception { rejected(claims().claim("email_verified", false)); }
    @Test void rejectsNonceSubstitution() throws Exception { rejected(claims().claim("nonce", "different-nonce")); }
    @Test void rejectsFutureIssueTime() throws Exception { rejected(claims().issueTime(Date.from(Instant.now().plusSeconds(120)))); }
    @Test void rejectsAuthorizedPartySubstitution() throws Exception { rejected(claims().claim("azp", "another-app")); }
    @Test void rejectsTokenWithoutExpiration() throws Exception { rejected(claims().expirationTime(null)); }
    @Test void rejectsSignatureFromAnotherKey() throws Exception {
        var other = KeyPairGenerator.getInstance("RSA"); other.initialize(2048);
        String forged = signed(claims(), other.generateKeyPair());
        assertThatThrownBy(() -> service.verify(forged, request)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void requiresBrowserCookieAndRejectsDuplicateCookies() throws Exception {
        String token = signed(claims(), keys);
        assertThatThrownBy(() -> service.verify(token, new MockHttpServletRequest())).isInstanceOf(ResponseStatusException.class);
        request.setCookies(new Cookie(GoogleIdentityService.NONCE_COOKIE, nonce), new Cookie(GoogleIdentityService.NONCE_COOKIE, nonce));
        assertThatThrownBy(() -> service.verify(token, request)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void thirdPartyEmailIsNotAuthoritativeAndWorkspaceIs() throws Exception {
        var identity = service.verify(signed(claims().claim("email", "learner@example.test"), keys), request);
        assertThat(identity.authoritativeEmail()).isFalse();
        when(values.getAndDelete(anyString())).thenReturn("1");
        assertThat(service.verify(signed(claims().claim("email", "learner@example.test").claim("hd", "example.test"), keys), request)
                .authoritativeEmail()).isTrue();
    }
    @Test void nonceCookieIsProtectedAndConfigurationCanBeDisabled() {
        var response = new MockHttpServletResponse(); service.challenge(response);
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/auth/google", "Max-Age=300");
        service.clearChallenge(response);
        assertThat(response.getHeaders("Set-Cookie").get(1)).contains("Max-Age=0");
        var disabled = new GoogleIdentityService("", true, redis, token -> {throw new AssertionError();});
        assertThatThrownBy(() -> disabled.challenge(new MockHttpServletResponse())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void redisFailureDoesNotExposeItsConnectionOrConsumeIdentity() throws Exception {
        when(values.getAndDelete(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret-redis-url"));
        String token = signed(claims(), keys);
        assertThatThrownBy(() -> service.verify(token, request)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503").hasMessageNotContaining("secret-redis-url");
    }
}
