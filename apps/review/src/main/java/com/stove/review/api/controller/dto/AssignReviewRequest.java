package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignReviewRequest(@NotBlank @Size(max = 100) String assignee, Long expectedVersion) {
}
