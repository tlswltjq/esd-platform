package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotNull;

public record CreateSubmissionRequest(
        @NotNull Long metadataRevisionId,
        @NotNull Long pricingRevisionId,
        @NotNull Long ratingRevisionId,
        @NotNull Long buildId
) {
}
