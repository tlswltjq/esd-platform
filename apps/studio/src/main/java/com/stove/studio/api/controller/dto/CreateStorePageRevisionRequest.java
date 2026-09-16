package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStorePageRevisionRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 500) String shortDescription,
        @NotBlank @Size(max = 30) String platform,
        @NotBlank @Size(max = 1000) String minimumRequirements
) {
}
