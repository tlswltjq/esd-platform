package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "ci_trust_policy", uniqueConstraints = @UniqueConstraint(
        name = "uk_ci_trust_scope", columnNames = {"gameId", "provider", "repository", "refPattern", "platform"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CiTrustPolicy extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private Long workspaceId;
    @Column(nullable = false, length = 30) private String provider;
    @Column(nullable = false, length = 300) private String repository;
    @Column(nullable = false, length = 300) private String refPattern;
    @Column(nullable = false, length = 30) private String platform;
    @Column(length = 300) private String protectedRefPattern;
    @Column(length = 100) private String requiredEnvironment;

    private CiTrustPolicy(Long gameId, Long workspaceId, String provider, String repository,
                          String refPattern, String platform, String protectedRefPattern,
                          String requiredEnvironment) {
        this.gameId = gameId;
        this.workspaceId = workspaceId;
        this.provider = provider;
        this.repository = repository;
        this.refPattern = refPattern;
        this.platform = platform;
        this.protectedRefPattern = protectedRefPattern;
        this.requiredEnvironment = requiredEnvironment;
    }

    public static CiTrustPolicy create(Long gameId, Long workspaceId, String provider,
                                       String repository, String refPattern, String platform,
                                       String protectedRefPattern, String requiredEnvironment) {
        return new CiTrustPolicy(gameId, workspaceId, provider, repository, refPattern,
                platform, protectedRefPattern, requiredEnvironment);
    }
}
