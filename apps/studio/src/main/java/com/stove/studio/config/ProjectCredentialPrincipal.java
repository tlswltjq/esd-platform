package com.stove.studio.config;

public record ProjectCredentialPrincipal(Long credentialId, Long gameId, Long workspaceId,
                                         String allowedRepository, String allowedRef,
                                         String allowedPlatform, boolean releaseAllowed,
                                         String sourceProvider, String sourceEnvironment) {
}
