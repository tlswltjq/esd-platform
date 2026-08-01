package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectRequest(@NotBlank String reasonCode, @NotBlank String reason) {
}
