package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.IssuedProjectCredential;
import com.stove.studio.core.domain.ProjectCredential;
import com.stove.studio.core.domain.ProjectCredentialRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectCredentialService {

    private static final String TOKEN_PREFIX = "esd_ci_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectCredentialRepository repository;
    private final GameProjectService projectService;

    public IssuedProjectCredential issue(Long gameId, Long workspaceId, String name, Instant expiresAt) {
        projectService.requireOwned(gameId, workspaceId);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "만료 시각은 현재보다 미래여야 합니다.");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String displayPrefix = token.substring(0, Math.min(15, token.length()));
        ProjectCredential credential = repository.save(ProjectCredential.issue(
                gameId, workspaceId, name, hash(token), displayPrefix, expiresAt));
        return new IssuedProjectCredential(credential, token);
    }

    public IssuedProjectCredential issueTrusted(Long gameId, Long workspaceId, String name,
                                                 Instant expiresAt, String allowedRepository,
                                                 String allowedRef, String allowedPlatform,
                                                 boolean releaseAllowed, String provider,
                                                 String environment, String oidcTokenId) {
        projectService.requireOwned(gameId, workspaceId);
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "OIDC 토큰이 이미 만료되었습니다.");
        }
        if (oidcTokenId == null || oidcTokenId.isBlank() || repository.existsByOidcTokenId(oidcTokenId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 교환했거나 식별할 수 없는 OIDC 토큰입니다.");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String displayPrefix = token.substring(0, Math.min(15, token.length()));
        ProjectCredential credential = repository.save(ProjectCredential.issueTrusted(
                gameId, workspaceId, name, hash(token), displayPrefix, expiresAt,
                allowedRepository, allowedRef, allowedPlatform, releaseAllowed, provider,
                environment, oidcTokenId));
        return new IssuedProjectCredential(credential, token);
    }

    @Transactional(readOnly = true)
    public List<ProjectCredential> list(Long gameId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        return repository.findByGameIdOrderByIdDesc(gameId);
    }

    public void revoke(Long gameId, Long credentialId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        ProjectCredential credential = repository.findById(credentialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "credentialId=" + credentialId));
        if (!credential.getGameId().equals(gameId) || !credential.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        credential.revoke();
    }

    public ProjectCredential authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 프로젝트 자격증명입니다.");
        }
        ProjectCredential credential = repository.findBySecretHash(hash(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED,
                        "유효하지 않은 프로젝트 자격증명입니다."));
        if (!credential.usableAt(Instant.now())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "만료되었거나 폐기된 프로젝트 자격증명입니다.");
        }
        credential.usedAt(Instant.now());
        return credential;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
