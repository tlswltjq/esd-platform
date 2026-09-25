package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.CiTrustPolicy;

public record CiTrustPolicyResponse(Long policyId, Long gameId, String provider, String repository,
                                    String refPattern, String platform, String protectedRefPattern,
                                    String requiredEnvironment) {
    public static CiTrustPolicyResponse from(CiTrustPolicy value) {
        return new CiTrustPolicyResponse(value.getId(), value.getGameId(), value.getProvider(),
                value.getRepository(), value.getRefPattern(), value.getPlatform(),
                value.getProtectedRefPattern(), value.getRequiredEnvironment());
    }
}
