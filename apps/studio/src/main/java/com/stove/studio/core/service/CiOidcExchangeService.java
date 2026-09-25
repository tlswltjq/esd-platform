package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.CiOidcProperties;
import com.stove.studio.core.domain.CiTrustPolicy;
import com.stove.studio.core.domain.CiTrustPolicyRepository;
import com.stove.studio.core.domain.IssuedProjectCredential;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CiOidcExchangeService {
    private final CiOidcProperties properties;
    private final CiTrustPolicyRepository policyRepository;
    private final ProjectCredentialService credentialService;
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    public IssuedProjectCredential exchange(Long gameId, String providerName,
                                             String platform, String rawToken) {
        String providerKey = providerName.toLowerCase(java.util.Locale.ROOT);
        CiOidcProperties.Provider provider = properties.providers().get(providerKey);
        if (provider == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 CI OIDC provider입니다.");
        }
        Jwt jwt;
        try {
            jwt = decoder(providerKey, provider).decode(rawToken);
        } catch (JwtException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "CI OIDC 토큰 검증에 실패했습니다.");
        }
        if (!jwt.getAudience().contains(provider.audience())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "OIDC audience가 일치하지 않습니다.");
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "OIDC subject가 필요합니다.");
        }
        String sourceRepository = requiredClaim(jwt, provider.repositoryClaim());
        String sourceRef = requiredClaim(jwt, provider.refClaim());
        String environment = optionalClaim(jwt, provider.environmentClaim());
        CiTrustPolicy policy = policyRepository.findByGameIdAndProviderAndRepository(
                        gameId, providerKey, sourceRepository).stream()
                .filter(value -> value.getPlatform().equalsIgnoreCase(platform))
                .filter(value -> matches(value.getRefPattern(), sourceRef))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN,
                        "repository/ref/platform이 프로젝트 신뢰 정책과 일치하지 않습니다."));
        boolean protectedRef = policy.getProtectedRefPattern() != null
                && matches(policy.getProtectedRefPattern(), sourceRef);
        boolean environmentApproved = policy.getRequiredEnvironment() == null
                || policy.getRequiredEnvironment().equals(environment);
        boolean releaseAllowed = protectedRef && environmentApproved;
        Instant now = Instant.now();
        Instant tokenExpiry = jwt.getExpiresAt();
        if (tokenExpiry == null || !tokenExpiry.isAfter(now)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "만료 시각이 유효한 OIDC 토큰이 필요합니다.");
        }
        Instant credentialLimit = now.plus(properties.credentialTtl());
        Instant expiresAt = tokenExpiry.isBefore(credentialLimit) ? tokenExpiry : credentialLimit;
        String tokenId = jwt.getId() == null ? sha256(rawToken) : sha256(providerKey + ":" + jwt.getId());
        return credentialService.issueTrusted(gameId, policy.getWorkspaceId(),
                "oidc:" + providerKey + ":" + subject, expiresAt,
                sourceRepository, sourceRef, policy.getPlatform(), releaseAllowed,
                providerKey, environment, tokenId);
    }

    private JwtDecoder decoder(String key, CiOidcProperties.Provider provider) {
        return decoders.computeIfAbsent(key, ignored -> {
            if (provider.jwkSetUri() == null || provider.jwkSetUri().isBlank()) {
                return JwtDecoders.fromIssuerLocation(provider.issuer());
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwkSetUri()).build();
            OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(provider.issuer());
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validator));
            return decoder;
        });
    }

    private String requiredClaim(Jwt jwt, String claimName) {
        String value = optionalClaim(jwt, claimName);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "OIDC 필수 claim이 없습니다: " + claimName);
        }
        return value;
    }

    private String optionalClaim(Jwt jwt, String claimName) {
        if (claimName == null || claimName.isBlank()) return null;
        Object value = jwt.getClaims().get(claimName);
        return value == null ? null : value.toString();
    }

    private boolean matches(String glob, String value) {
        String regex = java.util.Arrays.stream(glob.split("\\*", -1))
                .map(Pattern::quote).collect(Collectors.joining(".*", "^", "$"));
        return value.matches(regex);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
