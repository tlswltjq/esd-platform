package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.IssuedProjectCredential;
import java.time.Instant;

public record CiOidcExchangeResponse(String credential, Instant expiresAt,
                                     String repository, String sourceRef,
                                     String platform, boolean releaseAllowed) {
    public static CiOidcExchangeResponse from(IssuedProjectCredential value) {
        return new CiOidcExchangeResponse(value.token(), value.credential().getExpiresAt(),
                value.credential().getAllowedRepository(), value.credential().getAllowedRef(),
                value.credential().getAllowedPlatform(), value.credential().isReleaseAllowed());
    }
}
