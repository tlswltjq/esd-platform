package com.stove.studio.api.controller.dto;

import java.time.Instant;
import com.stove.studio.core.domain.ReleaseChannel;
import com.stove.studio.core.domain.ReleaseChangeType;
import jakarta.validation.constraints.Size;

public record CreateReleaseRequest(Instant publishAt, @Size(max = 50) String timeZone,
                                   ReleaseChannel channel, ReleaseChangeType changeType) {
}
