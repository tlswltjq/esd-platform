package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.NewProject;
import com.stove.studio.core.domain.ProductKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateProjectRequest(
        @NotBlank String productCode,
        @NotBlank String title,
        @PositiveOrZero long price,
        String currency,
        ProductKind productKind,
        Long parentGameId,
        @Size(max = 100) String editionName,
        @Size(max = 50) List<@NotNull Long> bundleGameIds
) {
    public CreateProjectRequest(String productCode, String title, long price, String currency) {
        this(productCode, title, price, currency, ProductKind.BASIC, null, null, List.of());
    }

    public CreateProjectRequest {
        currency = currency == null ? "KRW" : currency;
    }

    public NewProject toCommand(Long workspaceId) {
        return new NewProject(productCode, title, workspaceId, price, currency, false,
                productKind, parentGameId, editionName, bundleGameIds);
    }
}
