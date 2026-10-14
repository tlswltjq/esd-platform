package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.*;

public record RejectProjectReviewRequest(@NotBlank @Size(max = 2000) String feedback) {}
