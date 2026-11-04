package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.project.StorePage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateStorePageRequest(
        @Positive long contentVersion,
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 500) String shortDescription,
        @Size(max = 1000) String thumbnailUrl,
        @Size(max = 1000) String videoUrl,
        @NotNull @Size(max = 100000) String content,
        @PositiveOrZero long price, @NotNull @Size(max = 3) List<String> genres) {
    public StorePage page() {
        return new StorePage(title, shortDescription, thumbnailUrl, videoUrl, content, price, genres);
    }
}
