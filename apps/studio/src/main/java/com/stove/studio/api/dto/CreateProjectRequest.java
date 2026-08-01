package com.stove.studio.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateProjectRequest(
        @NotBlank String productCode,
        @NotBlank String title,
        @NotNull Long sellerId,
        @PositiveOrZero long price,
        String currency,
        boolean selfRated
) {
    public CreateProjectRequest {
        currency = currency == null ? "KRW" : currency;
    }
}
