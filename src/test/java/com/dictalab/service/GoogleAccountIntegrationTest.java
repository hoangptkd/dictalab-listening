package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.GoogleIdentityService.Identity;
import com.dictalab.exception.AuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class GoogleAccountIntegrationTest {
    @Autowired AccountService accounts;
    @Autowired AppUserRepository users;

    Identity identity(boolean authoritative) {
        String id = UUID.randomUUID().toString();
        return new Identity(id, id + (authoritative ? "@gmail.com" : "@example.test"), "Google Learner", authoritative);
    }
    @Test void createsOneAccountAndUsesSubjectAfterGoogleEmailChanges() {
        var identity = identity(true);
        var first = accounts.loginGoogle(identity).user();
        assertThat(first.getRole()).isEqualTo("USER");
        assertThat(first.isEmailVerified()).isTrue();
        assertThat(first.getGoogleSubject()).isEqualTo(identity.subject());
        var again = accounts.loginGoogle(new Identity(identity.subject(), "changed@gmail.com", "Changed", true)).user();
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getEmail()).isEqualTo(identity.email());
        assertThat(again.getLastLoginAt()).isNotNull();
    }
    @Test void authoritativeGoogleLinksExistingUserAndPreservesPasswordAndRole() {
        var identity = identity(true);
        var owner = new AppUser(identity.email(), "Existing Learner", "original-password-hash", Instant.now());
        owner.verifyEmail(Instant.now());
        owner.setRole("ADMIN", Instant.now());
        owner = users.saveAndFlush(owner);
        var login = accounts.loginGoogle(identity).user();
        assertThat(login.getId()).isEqualTo(owner.getId());
        assertThat(login.getPasswordHash()).isEqualTo("original-password-hash");
        assertThat(login.getRole()).isEqualTo("ADMIN");
        assertThat(login.getDisplayName()).isEqualTo("Existing Learner");
    }
    @Test void thirdPartyGoogleEmailCannotTakeOverExistingAccount() {
        var identity = identity(false);
        users.saveAndFlush(new AppUser(identity.email(), "Existing", "unchanged", Instant.now()));
        assertThatThrownBy(() -> accounts.loginGoogle(identity)).isInstanceOf(AuthenticationFailedException.class);
        assertThat(users.findByEmail(identity.email()).orElseThrow().getGoogleSubject()).isNull();
    }
    @Test void newThirdPartyGoogleEmailDoesNotBecomeVerifiedForLocalAccountOperations() {
        assertThat(accounts.loginGoogle(identity(false)).user().isEmailVerified()).isFalse();
    }
    @Test void unverifiedPasswordAccountCannotBeSilentlyLinkedToGoogle() {
        var identity = identity(true);
        users.saveAndFlush(new AppUser(identity.email(), "Unverified", "unknown-password-owner", Instant.now()));
        assertThatThrownBy(() -> accounts.loginGoogle(identity)).isInstanceOf(AuthenticationFailedException.class);
        assertThat(users.findByEmail(identity.email()).orElseThrow().getGoogleSubject()).isNull();
    }
    @Test void lockedUserAndConflictingGoogleSubjectCannotSignIn() {
        var identity = identity(true);
        var user = accounts.loginGoogle(identity).user();
        user.setEnabled(false, Instant.now()); user = users.saveAndFlush(user);
        assertThatThrownBy(() -> accounts.loginGoogle(identity)).isInstanceOf(AuthenticationFailedException.class);
        user.setEnabled(true, Instant.now()); users.saveAndFlush(user);
        assertThatThrownBy(() -> accounts.loginGoogle(new Identity("different-google", identity.email(), "Other", true)))
                .isInstanceOf(AuthenticationFailedException.class);
    }
    @Test void concurrentFirstLoginDoesNotCreateDuplicateAccounts() throws Exception {
        var identity = identity(true);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> accounts.loginGoogle(identity).user().getId());
            var second = pool.submit(() -> accounts.loginGoogle(identity).user().getId());
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
