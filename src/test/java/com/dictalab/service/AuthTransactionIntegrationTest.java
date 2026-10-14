package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.dto.AuthDtos;
import com.dictalab.exception.AuthenticationFailedException;
import com.dictalab.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** A one-connection pool makes connection retention while hashing observable and reproducible. */
@SpringBootTest(properties={"spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"})
class AuthTransactionIntegrationTest {
    @Autowired AccountService accounts;
    @Autowired AccountManagementService management;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate db;
    @MockitoSpyBean PasswordEncoder encoder;
    @Autowired javax.sql.DataSource dataSource;
    static final String PASSWORD = "Test-password-2026";

    @org.junit.jupiter.api.BeforeEach void oneConnectionAfterFlywayStartup() throws Exception {
        var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        pool.setMaximumPoolSize(1);
        db.queryForObject("SELECT 1", Integer.class);
    }

    @Test void registrationHashesOnceOutsideTransactionAndPersistsLogin() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(encoder).encode(PASSWORD);
        String email = "hash-" + UUID.randomUUID() + "@test.local";
        var result = accounts.register(new AuthDtos.RegisterRequest("Learner", email, PASSWORD));
        verify(encoder, times(1)).encode(PASSWORD);
        verify(encoder, never()).matches(anyString(), anyString());
        assertThat(result.authentication().isAuthenticated()).isTrue();
        assertThat(users.findById(result.user().getId()).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings={"disable","password","revoke"})
    void loginRejectsAccountChangeDuringHashWithoutHoldingConnection(String change) throws Exception {
        AppUser owner = owner(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(encoder).matches(eq(PASSWORD), anyString());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var login = pool.submit(() -> accounts.login(new AuthDtos.LoginRequest(owner.getEmail(), PASSWORD)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // This must acquire the only connection while BCrypt is blocked on the other thread.
            String column = switch(change) {
                case "disable" -> "enabled=FALSE,";
                case "password" -> "password_hash='changed',";
                default -> "";
            };
            db.update("UPDATE app_users SET " + column + "auth_version=auth_version+1 WHERE id=?", owner.getId());
            release.countDown();
            assertThatThrownBy(() -> login.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AuthenticationFailedException.class);
            assertThat(users.findById(owner.getId()).orElseThrow().getLastLoginAt()).isNull();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void resetRechecksConsumedTokenAfterHashWithoutChangingPassword() throws Exception {
        AppUser owner = owner(); String token = UUID.randomUUID().toString();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("INSERT INTO account_tokens(token_hash,user_id,purpose,created_at,expires_at) VALUES (?,?,'RESET_PASSWORD',CURRENT_TIMESTAMP,?)",
                hash, owner.getId(), java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(encoder).encode("New-password-2026");
        var pool = Executors.newSingleThreadExecutor();
        try {
            var reset = pool.submit(() -> management.consumeToken(token, "RESET_PASSWORD", "New-password-2026"));
            if (!entered.await(10, TimeUnit.SECONDS)) {
                reset.get(1, TimeUnit.SECONDS);
                fail("Reset did not reach password encoder");
            }
            db.update("UPDATE account_tokens SET used_at=CURRENT_TIMESTAMP WHERE token_hash=?", hash);
            release.countDown();
            assertThatThrownBy(() -> reset.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(users.findById(owner.getId()).orElseThrow().getPasswordHash()).isEqualTo(owner.getPasswordHash());
            assertThat(db.queryForObject("SELECT COUNT(*) FROM account_audit WHERE subject_id=?", Long.class, owner.getId())).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void passwordChangeRejectsConcurrentRevocation() throws Exception {
        AppUser owner = owner(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(encoder).encode("New-password-2026");
        var pool = Executors.newSingleThreadExecutor();
        try {
            var change = pool.submit(() -> management.changePassword(owner.getId(), PASSWORD, "New-password-2026"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            management.revokeSessions(owner.getId()); release.countDown();
            assertThatThrownBy(() -> change.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResponseStatusException.class);
            assertThat(users.findById(owner.getId()).orElseThrow().getPasswordHash()).isEqualTo(owner.getPasswordHash());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private AppUser owner() {
        return users.saveAndFlush(new AppUser("auth-tx-" + UUID.randomUUID() + "@test.local", "Learner",
                encoder.encode(PASSWORD), Instant.now()));
    }
}
