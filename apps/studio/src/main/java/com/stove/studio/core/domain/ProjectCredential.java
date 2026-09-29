package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "project_credential")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectCredential extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long gameId;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String secretHash;

    @Column(nullable = false, length = 16)
    private String tokenPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectCredentialStatus status;

    private Instant expiresAt;

    private Instant lastUsedAt;
    @Column(length = 300) private String allowedRepository;
    @Column(length = 300) private String allowedRef;
    @Column(length = 30) private String allowedPlatform;
    @Column(nullable = false) private boolean releaseAllowed;
    @Column(length = 30) private String sourceProvider;
    @Column(length = 100) private String sourceEnvironment;
    @Column(unique = true, length = 200) private String oidcTokenId;

    public static ProjectCredential issue(Long gameId, Long workspaceId, String name,
                                          String secretHash, String tokenPrefix, Instant expiresAt) {
        ProjectCredential credential = new ProjectCredential();
        credential.gameId = gameId;
        credential.workspaceId = workspaceId;
        credential.name = name;
        credential.secretHash = secretHash;
        credential.tokenPrefix = tokenPrefix;
        credential.status = ProjectCredentialStatus.ACTIVE;
        credential.expiresAt = expiresAt;
        return credential;
    }

    public static ProjectCredential issueTrusted(Long gameId, Long workspaceId, String name,
                                                  String secretHash, String tokenPrefix, Instant expiresAt,
                                                  String repository, String sourceRef, String platform,
                                                  boolean releaseAllowed, String provider,
                                                  String environment, String oidcTokenId) {
        ProjectCredential credential = issue(gameId, workspaceId, name, secretHash, tokenPrefix, expiresAt);
        credential.allowedRepository = repository;
        credential.allowedRef = sourceRef;
        credential.allowedPlatform = platform;
        credential.releaseAllowed = releaseAllowed;
        credential.sourceProvider = provider;
        credential.sourceEnvironment = environment;
        credential.oidcTokenId = oidcTokenId;
        return credential;
    }

    public boolean usableAt(Instant now) {
        return status == ProjectCredentialStatus.ACTIVE
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void usedAt(Instant now) {
        this.lastUsedAt = now;
    }

    public void revoke() {
        this.status = ProjectCredentialStatus.REVOKED;
    }
}
