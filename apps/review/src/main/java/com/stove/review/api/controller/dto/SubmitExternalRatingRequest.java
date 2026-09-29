package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record SubmitExternalRatingRequest(
        @NotBlank @Size(max = 100) String applicationNumber,
        @NotNull Instant submittedAt,
        @NotBlank @Size(max = 500) @Pattern(regexp = "^https://[^\\s]+$") String evidenceUrl
) {
}
