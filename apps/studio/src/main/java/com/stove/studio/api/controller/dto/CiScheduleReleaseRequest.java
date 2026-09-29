package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CiScheduleReleaseRequest(@NotNull Long submissionId, @NotNull Instant publishAt,
                                       @Size(max = 50) String timeZone) {
}
