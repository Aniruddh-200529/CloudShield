package com.cloudshield.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "user_accounts")
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, unique = true, length = 80) private String username;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private UserRole role;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "mfa_enabled", nullable = false) private boolean mfaEnabled;
    @Column(name = "mfa_secret_ciphertext", length = 512) private String mfaSecretCiphertext;
    @Column(name = "mfa_last_used_step") private Long mfaLastUsedStep;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected UserAccount() {}
    public UserAccount(String username, String displayName, String passwordHash, UserRole role) {
        this.username = username; this.displayName = displayName; this.passwordHash = passwordHash; this.role = role; this.enabled = true;
    }
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public void changeRole(UserRole role) { this.role = role; }
    public void changeDisplayName(String displayName) { this.displayName = displayName; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void enableMfa(String encryptedSecret, long verifiedStep) { this.mfaSecretCiphertext = encryptedSecret; this.mfaLastUsedStep = verifiedStep; this.mfaEnabled = true; }
    public void recordMfaStep(long step) { this.mfaLastUsedStep = step; }
    public void disableMfa() { this.mfaEnabled = false; this.mfaSecretCiphertext = null; this.mfaLastUsedStep = null; }
    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public UserRole getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public String getMfaSecretCiphertext() { return mfaSecretCiphertext; }
    public Long getMfaLastUsedStep() { return mfaLastUsedStep; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
