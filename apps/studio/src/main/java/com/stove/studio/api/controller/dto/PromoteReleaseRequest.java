package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.ReleaseChannel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record PromoteReleaseRequest(@NotNull ReleaseChannel targetChannel, Instant publishAt,
                                    @Size(max = 50) String timeZone) {
}
