package com.stove.studio.core.domain.access;

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

/**
 * 프로젝트 작업 권한을 부여할 CI 실행의 신뢰 조건.
 */
@Entity
@Getter
@Table(name = "ci_trust_policy", uniqueConstraints = @UniqueConstraint(
        name = "uk_ci_trust_scope", columnNames = {"gameId", "provider", "repository", "refPattern", "platform"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CiTrustPolicy extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 프로젝트와 권한을 소유한 작업 공간 식별자. */
    @Column(nullable = false) private Long workspaceId;
    /** 신뢰할 CI 제공자 이름. */
    @Column(nullable = false, length = 30) private String provider;
    /** CI 인증을 허용할 소스 저장소. */
    @Column(nullable = false, length = 300) private String repository;
    /** 허용할 브랜치·태그 참조 패턴. */
    @Column(nullable = false, length = 300) private String refPattern;
    /** 빌드가 실행될 대상 플랫폼. */
    @Column(nullable = false, length = 30) private String platform;
    /** 출시 권한 부여에 필요한 보호된 소스 참조 패턴. */
    @Column(length = 300) private String protectedRefPattern;
    /** 출시 권한 부여에 필요한 CI 실행 환경. */
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
