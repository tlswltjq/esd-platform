package com.stove.studio.core.domain.access;

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

/**
 * 프로젝트 자동화 작업에 사용할 인증 정보와 권한 범위.
 */
@Entity
@Getter
@Table(name = "project_credential")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectCredential extends BaseTimeEntity {

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false)
    private Long gameId;

    /** 프로젝트와 권한을 소유한 작업 공간 식별자. */
    @Column(nullable = false)
    private Long workspaceId;

    /** 조회 화면에 표시할 이름. */
    @Column(nullable = false, length = 100)
    private String name;

    /** 인증 토큰 검증용 해시. */
    @Column(nullable = false, unique = true, length = 64)
    private String secretHash;

    /** 원문 노출 없이 인증 정보를 구분할 토큰 앞부분. */
    @Column(nullable = false, length = 16)
    private String tokenPrefix;

    /** 인증 정보의 활성 또는 폐기 상태. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectCredentialStatus status;

    /** 인증 정보의 만료 시각으로, 없으면 기간 제한 없이 사용. */
    private Instant expiresAt;

    /** 인증 정보를 마지막으로 사용한 시각. */
    private Instant lastUsedAt;
    /** 인증 정보로 접근을 허용한 소스 저장소. */
    @Column(length = 300) private String allowedRepository;
    /** 인증 정보로 작업을 허용한 브랜치·태그 참조. */
    @Column(length = 300) private String allowedRef;
    /** 인증 정보로 빌드를 업로드할 수 있는 플랫폼. */
    @Column(length = 30) private String allowedPlatform;
    /** 신뢰 정책에 따라 출시 작업을 허용하는지 여부. */
    @Column(nullable = false) private boolean releaseAllowed;
    /** 인증 정보를 발급하게 한 CI 제공자. */
    @Column(length = 30) private String sourceProvider;
    /** 인증을 요청한 CI 실행 환경. */
    @Column(length = 100) private String sourceEnvironment;
    /** OIDC 토큰의 중복 교환을 방지하는 식별자. */
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
