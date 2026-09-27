package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import com.stove.studio.core.domain.StorePageContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CreateStorePageRevisionRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 500) String shortDescription,
        @Size(max = 10000) String detailedDescription,
        @Valid Map<@Size(min = 2, max = 10) String, LocalizedContentRequest> localizations,
        @Size(max = 10) List<@NotBlank @Size(max = 50) String> genres,
        @Size(max = 30) List<@NotBlank @Size(max = 50) String> tags,
        @Size(max = 200) String developer,
        @Size(max = 200) String publisher,
        @Size(max = 20) List<@NotBlank @Size(max = 500) String> screenshots,
        @Size(max = 5) List<@NotBlank @Size(max = 500) String> trailers,
        @Size(max = 500) String iconUrl,
        @Size(max = 500) String coverUrl,
        @Size(max = 30) List<@NotBlank @Size(max = 10) String> supportedLanguages,
        @NotBlank @Size(max = 30) String platform,
        @NotBlank @Size(max = 1000) String minimumRequirements,
        @Size(max = 1000) String recommendedRequirements,
        @Size(max = 30) List<@NotBlank @Size(max = 50) String> features,
        @Size(max = 500) String supportUrl,
        @Size(max = 500) String privacyPolicyUrl,
        @Size(max = 500) String eulaUrl,
        @Size(max = 250) List<@Size(min = 2, max = 2) String> salesCountries,
        Map<@Size(min = 3, max = 3) String, @PositiveOrZero Long> prices,
        Boolean draft
) {
    public StorePageContent toDomain() {
        Map<String, StorePageContent.LocalizedContent> localized = new LinkedHashMap<>();
        if (localizations != null) {
            localizations.forEach((language, value) -> localized.put(language,
                    new StorePageContent.LocalizedContent(value.title(), value.shortDescription(),
                            value.detailedDescription())));
        }
        return new StorePageContent(title, shortDescription,
                detailedDescription == null ? shortDescription : detailedDescription,
                localized, genres, tags,
                developer == null ? "" : developer, publisher == null ? "" : publisher,
                screenshots, trailers, iconUrl, coverUrl, supportedLanguages, platform,
                minimumRequirements,
                recommendedRequirements == null ? minimumRequirements : recommendedRequirements,
                features, supportUrl == null ? "" : supportUrl,
                privacyPolicyUrl == null ? "" : privacyPolicyUrl,
                eulaUrl == null ? "" : eulaUrl, salesCountries, prices);
    }

    public boolean isDraft() {
        return Boolean.TRUE.equals(draft);
    }

    public record LocalizedContentRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 500) String shortDescription,
            @NotBlank @Size(max = 10000) String detailedDescription) {
    }
}
