package com.stove.review.api.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record UpdateChecklistRequest(
        @NotEmpty @Size(max = 100) Map<@Size(max = 100) String, Boolean> checklist,
        @Size(max = 2000) String internalMemo,
        Long expectedVersion) {
}
