package com.condertech.protegeela.user.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 30)
    private String phone = "";

    @Column(name = "privacy_mode", nullable = false, length = 20)
    private String privacyMode = "standard";

    public void updateProfile(String name, String phone, String privacyMode) {
        this.name = name.strip();
        this.phone = phone.strip();
        this.privacyMode = privacyMode;
    }

    @JsonIgnore
    @Column(nullable = false, length = 60)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @JsonIgnore
    @Column(name = "email_verification_code_hash", length = 60)
    private String emailVerificationCodeHash;

    @JsonIgnore
    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email;
        this.password = passwordHash;
        this.role = UserRole.USER;
        this.enabled = true;
        this.emailVerified = false;
    }

    public void startEmailVerification(String codeHash, Instant expiresAt) {
        this.emailVerificationCodeHash = codeHash;
        this.emailVerificationExpiresAt = expiresAt;
    }

    public void verifyEmail() {
        this.emailVerified = true;
        this.emailVerificationCodeHash = null;
        this.emailVerificationExpiresAt = null;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
