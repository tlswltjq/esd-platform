package com.stove.studio.core.domain;

import java.time.Instant;

public record TesterInstallation(Long buildId, ReleaseChannel channel,
                                 String downloadUrl, Instant expiresAt) {
}
