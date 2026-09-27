package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewTransitionRequest(
        @NotBlank @Size(max = 30) String reasonCode,
        @Size(max = 2000) String internalMemo,
        @Size(max = 500) String evidenceUrl,
        Long expectedVersion) {
}
