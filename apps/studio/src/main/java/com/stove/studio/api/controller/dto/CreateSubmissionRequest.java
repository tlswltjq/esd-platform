package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateSubmissionRequest(
        @NotNull Long metadataRevisionId,
        @NotNull Long pricingRevisionId,
        @NotNull Long ratingRevisionId,
        @NotNull Long buildId,
        @Size(max = 19) List<@NotNull Long> additionalBuildIds
) {
    public CreateSubmissionRequest(Long metadataRevisionId, Long pricingRevisionId,
                                   Long ratingRevisionId, Long buildId) {
        this(metadataRevisionId, pricingRevisionId, ratingRevisionId, buildId, List.of());
    }

    public CreateSubmissionRequest {
        additionalBuildIds = additionalBuildIds == null ? List.of() : List.copyOf(additionalBuildIds);
    }
}
