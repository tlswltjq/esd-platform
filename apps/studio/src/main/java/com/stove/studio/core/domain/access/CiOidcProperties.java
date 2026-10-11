package com.stove.studio.core.domain.access;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CI OIDC 토큰 검증과 단기 인증 정보 발급 설정.
 *
 * @param credentialTtl OIDC 인증을 교환해 발급한 프로젝트 인증 정보의 유효 기간.
 * @param providers CI 제공자 이름별 OIDC 검증 설정.
 */
@ConfigurationProperties(prefix = "stove.ci.oidc")
public record CiOidcProperties(Duration credentialTtl, Map<String, Provider> providers) {
    public CiOidcProperties {
        credentialTtl = credentialTtl == null ? Duration.ofMinutes(15) : credentialTtl;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    /**
     * CI 제공자별 OIDC 서명 검증 및 클레임 해석 설정.
     *
     * @param issuer 허용할 OIDC 토큰 발급자 식별 URL.
     * @param jwkSetUri 토큰 서명을 검증할 공개 키 집합 URL.
     * @param audience OIDC 토큰에서 요구하는 수신 대상.
     * @param repositoryClaim 저장소 이름을 읽을 토큰 클레임 이름.
     * @param refClaim 브랜치·태그 참조를 읽을 토큰 클레임 이름.
     * @param environmentClaim CI 실행 환경을 읽을 토큰 클레임 이름.
     */
    public record Provider(String issuer, String jwkSetUri, String audience,
                           String repositoryClaim, String refClaim, String environmentClaim) {
    }
}
