package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.NewProject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateProjectRequest(
        @NotBlank String productCode,
        @NotBlank String title,
        @PositiveOrZero long price,
        String currency
) {
    public CreateProjectRequest {
        currency = currency == null ? "KRW" : currency;
    }

    public NewProject toCommand(Long workspaceId) {
        return new NewProject(productCode, title, workspaceId, price, currency, false);
    }
}
