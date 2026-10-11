package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.release.ReleaseChangeType;
import com.stove.studio.core.domain.release.ReleaseChannel;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateReleaseRequest(Instant publishAt, @Size(max = 50) String timeZone,
                                   ReleaseChannel channel, ReleaseChangeType changeType) {
}
