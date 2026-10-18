package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectProjectReviewRequest(@NotBlank @Size(max = 2000) String feedback) {}
