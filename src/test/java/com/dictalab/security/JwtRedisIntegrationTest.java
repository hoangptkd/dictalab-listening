package com.dictalab.security;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Redis and PostgreSQL, isolated by scripts/test-messaging.ps1. No provider calls. */
@EnabledIfEnvironmentVariable(named = "DICTALAB_MESSAGING_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.data.redis.url=redis://127.0.0.1:${TEST_REDIS_PORT}/14", "management.server.port=0"})
@AutoConfigureMockMvc
class JwtRedisIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtTokenService tokens;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RedisTokenStore store;
    @Autowired StringRedisTemplate redis;
    @org.springframework.boot.test.web.server.LocalServerPort int serverPort;
    @Autowired com.dictalab.room.RoomRegistry rooms;
    @Autowired com.dictalab.room.RoomWebSocketHandler sockets;
    private final Set<String> sessions = new HashSet<>();

    private AppUser account() {
        return users.saveAndFlush(new AppUser("jwt-" + UUID.randomUUID() + "@test.local", "JWT learner",
                passwords.encode("listen123"), Instant.now()));
    }

    private MvcResult login(AppUser user) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"listen123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        sessions.add(tokens.decode(access(result).getValue()).getClaimAsString("sid"));
        return result;
    }

    private Cookie access(MvcResult result) { return result.getResponse().getCookie(JwtTokenService.ACCESS_COOKIE); }
    private Cookie refresh(MvcResult result) { return result.getResponse().getCookie(JwtTokenService.REFRESH_COOKIE); }

    @AfterEach void clean() { for (String sid : sessions) store.revoke(sid); }

    @Test void cookiesCarryAccessJwtWhileRedisStoresOnlyHashesAndAbsoluteTtl() throws Exception {
        var user = account(); var login = login(user); var jwt = tokens.decode(access(login).getValue());
        assertThat(login.getRequest().getSession(false)).isNull();
        assertThat(access(login).isHttpOnly()).isTrue(); assertThat(access(login).getPath()).isEqualTo("/");
        assertThat(refresh(login).isHttpOnly()).isTrue(); assertThat(refresh(login).getPath()).isEqualTo("/api/auth");
        assertThat(refresh(login).getValue()).doesNotContain("eyJ");
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(600);
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "passwordHash", "email");
        String key = "{dictalab-auth}:v1:session:" + jwt.getClaimAsString("sid");
        assertThat(redis.getExpire(key)).isBetween(604790L, 604800L);
        assertThat(redis.opsForHash().entries(key).values()).doesNotContain(refresh(login).getValue());
        mvc.perform(get("/api/auth/me").cookie(access(login))).andExpect(status().isOk());
        mvc.perform(get("/admin").cookie(access(login))).andExpect(status().isForbidden());
        mvc.perform(get("/private-media/not-real.mp3").cookie(access(login))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").param("access_token", access(login).getValue())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(refresh(login))).andExpect(status().isForbidden());
    }

    @Test void rotationRejectsUnknownSecretsAndRevokesFamilyOnReusedRefresh() throws Exception {
        var login = login(account()); var old = refresh(login);
        var unknown = new Cookie(old.getName(), old.getValue().substring(0, 37) + "A".repeat(43));
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(unknown)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(access(login))).andExpect(status().isOk());
        var rotated = mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(old))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(refresh(rotated).getValue()).isNotEqualTo(old.getValue());
        var sid = tokens.decode(access(login).getValue()).getClaimAsString("sid");
        assertThat(store.session(sid).get("expires")).isNotNull();
        mvc.perform(get("/api/auth/me").cookie(access(rotated))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(access(rotated))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh(rotated))).andExpect(status().isUnauthorized());
    }

    @Test void concurrentRefreshAllowsOnlyOneRotationAndDetectsReplay() throws Exception {
        var user = account(); var login = login(user); String sid = tokens.decode(access(login).getValue()).getClaimAsString("sid");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(refresh(login).getValue().getBytes(StandardCharsets.UTF_8)));
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                String next = "hash-" + i;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try { store.rotate(sid, user.getId(), user.getAuthVersion(), hash, next); return true; }
                    catch (ResponseStatusException rejected) { assertThat(rejected.getStatusCode().value()).isEqualTo(401); return false; }
                }));
            }
            start.countDown(); int successes = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1); assertThat(store.active(sid, user.getId(), user.getAuthVersion())).isFalse();
        } finally { pool.shutdownNow(); }
    }

    @Test void logoutWithExpiredAccessRevokesCurrentSessionOnlyAndLogoutAllInvalidatesOthers() throws Exception {
        var user = account(); var first = login(user); var second = login(user);
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(refresh(first), expired(access(first))))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge(JwtTokenService.ACCESS_COOKIE, 0));
        mvc.perform(get("/api/auth/me").cookie(access(first))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(access(second))).andExpect(status().isOk());
        mvc.perform(post("/api/account/logout-all").with(csrf()).cookie(access(second))).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(access(second))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh(second))).andExpect(status().isUnauthorized());
        var latest = login(user);
        mvc.perform(get("/api/auth/me").cookie(access(latest))).andExpect(status().isOk());
    }

    @Test void passwordChangeAndAdminLockInvalidateAccessAndRefresh() throws Exception {
        var user = account(); var login = login(user);
        mvc.perform(post("/api/account/password").with(csrf()).cookie(access(login)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"listen123\",\"newPassword\":\"newpass456\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(access(login))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh(login))).andExpect(status().isUnauthorized());
        var target = account(); var targetLogin = login(target);
        var admin = account(); admin.promoteToAdmin(Instant.now()); users.saveAndFlush(admin);
        mvc.perform(put("/api/admin/users/" + target.getId() + "/state").with(user(DictaLabPrincipal.from(admin))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"reason\":\"JWT test\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(access(targetLogin))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh(targetLogin))).andExpect(status().isUnauthorized());
    }

    @Test void expiredOrTamperedJwtCannotAuthenticateButExpiredAccessCanRefresh() throws Exception {
        var login = login(account());
        mvc.perform(get("/api/auth/me").cookie(expired(access(login)))).andExpect(status().isUnauthorized());
        String token = access(login).getValue(); int signature = token.lastIndexOf('.') + 1;
        String tampered = token.substring(0, signature) + (token.charAt(signature) == 'A' ? 'B' : 'A') + token.substring(signature + 1);
        mvc.perform(get("/api/auth/me").cookie(new Cookie(JwtTokenService.ACCESS_COOKIE, tampered))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(expired(access(login)), refresh(login)))
                .andExpect(status().isNoContent());
    }

    @Test void deletedRedisSessionRejectsPreviouslyValidJwtAndRefresh() throws Exception {
        var login = login(account()); String sid = tokens.decode(access(login).getValue()).getClaimAsString("sid");
        store.revoke(sid);
        mvc.perform(get("/api/auth/me").cookie(access(login))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh(login))).andExpect(status().isUnauthorized());
    }

    @Test void repeatedBrowserLoginRetiresPresentedTokenFamily() throws Exception {
        var user = account(); var first = login(user);
        var next = mvc.perform(post("/api/auth/login").with(csrf()).cookie(refresh(first))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"listen123\"}"))
                .andExpect(status().isOk()).andReturn();
        sessions.add(tokens.decode(access(next).getValue()).getClaimAsString("sid"));
        mvc.perform(get("/api/auth/me").cookie(access(first))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(access(next))).andExpect(status().isOk());
    }

    @Test void redisOutageReturns503ForAuthenticationAndRefreshWithoutClearingCookies() throws Exception {
        var login = login(account());
        org.mockito.BDDMockito.willThrow(new RedisTokenStore.Unavailable()).given(store)
                .active(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        mvc.perform(get("/api/auth/me").cookie(access(login))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3")).andExpect(header().doesNotExist("Set-Cookie"));
        org.mockito.BDDMockito.willThrow(new RedisTokenStore.Unavailable()).given(store).session(org.mockito.ArgumentMatchers.anyString());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh(login))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3")).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test void realWebSocketUsesJwtCookieAndIdleSocketClosesAfterRevocation() throws Exception {
        var user = account(); var login = login(user);
        var room = rooms.createRoom(user.getId(), user.getDisplayName()).orElseThrow();
        var closed = new CompletableFuture<Integer>();
        var listener = new java.net.http.WebSocket.Listener() {
            @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }
            @Override public CompletionStage<?> onText(java.net.http.WebSocket socket, CharSequence data, boolean last) {
                socket.request(1); return CompletableFuture.completedFuture(null);
            }
            @Override public CompletionStage<?> onClose(java.net.http.WebSocket socket, int status, String reason) {
                closed.complete(status); return CompletableFuture.completedFuture(null);
            }
        };
        java.net.http.WebSocket ws = null;
        var client = java.net.http.HttpClient.newHttpClient();
        try {
            var uri = java.net.URI.create("ws://127.0.0.1:" + serverPort + "/ws/room/" + room.getRoomId());
            ws = client.newWebSocketBuilder().header("Origin", "http://localhost:18081")
                    .header("Cookie", JwtTokenService.ACCESS_COOKIE + "=" + access(login).getValue())
                    .buildAsync(uri, listener).get(10, TimeUnit.SECONDS);
            store.revoke(tokens.decode(access(login).getValue()).getClaimAsString("sid"));
            sockets.checkAuthentication();
            assertThat(closed.get(10, TimeUnit.SECONDS)).isEqualTo(1008);
        } finally {
            if (ws != null) ws.abort();
            rooms.leaveRoom(room.getRoomId(), user.getId());
        }
    }

    @Test void capacityAndTokenExpiryAreEnforcedByRedis() throws Exception {
        var user = account(); var limited = new RedisTokenStore(redis, 20000, 1);
        String sid = UUID.randomUUID().toString(); sessions.add(sid);
        limited.create(sid, user.getId(), 0, "first", Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> limited.create(UUID.randomUUID().toString(), user.getId(), 0, "next", Instant.now().plusSeconds(60)))
                .isInstanceOf(ResponseStatusException.class);
        redis.expire("{dictalab-auth}:v1:session:" + sid, java.time.Duration.ZERO);
        assertThat(limited.active(sid, user.getId(), 0)).isFalse();
        String replacement = UUID.randomUUID().toString(); sessions.add(replacement);
        limited.create(replacement, user.getId(), 1, "next", Instant.now().plusSeconds(60));
        assertThat(limited.active(replacement, user.getId(), 1)).isTrue();
    }

    private Cookie expired(Cookie access) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new OctetSequenceKey.Builder(
                Base64.getDecoder().decode("dGVzdC1vbmx5LWRpY3RhbGFiLXNpZ25pbmcta2V5LTMyaGFyYWN0ZXJz"))
                .keyID("1").algorithm(JWSAlgorithm.HS256).build())));
        var claims = JwtClaimsSet.builder().claims(map -> map.putAll(tokens.decode(access.getValue()).getClaims()))
                .issuedAt(Instant.now().minusSeconds(120)).expiresAt(Instant.now().minusSeconds(60)).build();
        return new Cookie(JwtTokenService.ACCESS_COOKIE, encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).keyId("1").build(), claims)).getTokenValue());
    }
}
