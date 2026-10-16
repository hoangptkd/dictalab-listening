package com.dictalab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 160)
    private String email;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "google_subject", unique = true, length = 255)
    private String googleSubject;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Version
    private long version;

    protected AppUser() {
    }

    public AppUser(String email, String displayName, String passwordHash, Instant now) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = "USER";
        this.enabled = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEmailVerified() { return emailVerified; }

    public String getGoogleSubject() { return googleSubject; }

    public void linkGoogle(String subject, Instant now) { this.googleSubject = subject; this.updatedAt = now; }

    public long getAuthVersion() { return authVersion; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public void rename(String name, Instant now) { this.displayName = name; this.updatedAt = now; }

    public void changePassword(String hash, Instant now) {
        this.passwordHash = hash;
        this.authVersion++;
        this.updatedAt = now;
    }

    public void changeEmail(String newEmail, Instant now) {
        this.email = newEmail;
        this.emailVerified = true;
        this.authVersion++;
        this.updatedAt = now;
    }

    public void verifyEmail(Instant now) { this.emailVerified = true; this.updatedAt = now; }

    public void revokeSessions(Instant now) { this.authVersion++; this.updatedAt = now; }

    public void setEnabled(boolean value, Instant now) {
        this.enabled = value;
        this.authVersion++;
        this.updatedAt = now;
    }

    public void setRole(String value, Instant now) {
        this.role = value;
        this.authVersion++;
        this.updatedAt = now;
    }

    public boolean promoteToAdmin(Instant now) {
        if ("ADMIN".equals(this.role)) {
            return false;
        }
        this.role = "ADMIN";
        this.updatedAt = now;
        return true;
    }
}
