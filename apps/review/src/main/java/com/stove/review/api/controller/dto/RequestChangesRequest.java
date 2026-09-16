package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequestChangesRequest(
        @NotBlank @Size(max = 30) String reasonCode,
        @NotBlank @Size(max = 1000) String feedback
) {
}
