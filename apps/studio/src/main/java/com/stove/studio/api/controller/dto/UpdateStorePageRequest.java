package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.project.StorePage;

import jakarta.validation.constraints.*;

public record UpdateStorePageRequest(
        @Positive long contentVersion,
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 500) String shortDescription,
        @Size(max = 1000) String thumbnailUrl,
        @Size(max = 1000) String videoUrl,
        @NotNull @Size(max = 100000) String content,
        @PositiveOrZero long price) {
    public StorePage page() {
        return new StorePage(title, shortDescription, thumbnailUrl, videoUrl, content, price);
    }
}
