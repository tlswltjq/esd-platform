package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AppealReviewRequest(@NotBlank @Size(max = 1000) String reason, Long expectedVersion) {
}
