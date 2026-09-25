package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.CiOidcProperties;
import com.stove.studio.core.domain.CiTrustPolicy;
import com.stove.studio.core.domain.CiTrustPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CiTrustPolicyService {
    private final CiTrustPolicyRepository repository;
    private final GameProjectService projectService;
    private final CiOidcProperties oidcProperties;

    public CiTrustPolicy create(Long gameId, Long workspaceId, String provider, String sourceRepository,
                                String refPattern, String platform, String protectedRefPattern,
                                String requiredEnvironment) {
        projectService.requireOwned(gameId, workspaceId);
        String normalizedProvider = provider.toLowerCase(java.util.Locale.ROOT);
        if (!oidcProperties.providers().containsKey(normalizedProvider)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 CI OIDC provider입니다.");
        }
        requireGlob(refPattern);
        if (protectedRefPattern != null) requireGlob(protectedRefPattern);
        return repository.save(CiTrustPolicy.create(gameId, workspaceId, normalizedProvider,
                sourceRepository, refPattern, platform.toUpperCase(java.util.Locale.ROOT),
                protectedRefPattern, requiredEnvironment));
    }

    @Transactional(readOnly = true)
    public List<CiTrustPolicy> list(Long gameId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        return repository.findByGameIdOrderByIdDesc(gameId);
    }

    public void delete(Long gameId, Long policyId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        CiTrustPolicy policy = repository.findById(policyId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "ciTrustPolicyId=" + policyId));
        repository.delete(policy);
    }

    private void requireGlob(String value) {
        if (value == null || value.isBlank() || value.length() > 300 || value.contains("..")) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "유효한 branch/tag glob이 필요합니다.");
        }
    }
}
