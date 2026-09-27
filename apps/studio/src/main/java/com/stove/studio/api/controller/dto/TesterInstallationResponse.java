package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.ReleaseChannel;
import com.stove.studio.core.domain.TesterInstallation;
import java.time.Instant;

public record TesterInstallationResponse(Long buildId, ReleaseChannel channel,
                                         String downloadUrl, Instant expiresAt) {
    public static TesterInstallationResponse from(TesterInstallation value) {
        return new TesterInstallationResponse(value.buildId(), value.channel(),
                value.downloadUrl(), value.expiresAt());
    }
}
