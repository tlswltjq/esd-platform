package com.stove.studio.core.domain;

import java.util.List;
import java.util.Map;

public record StorePageContent(
        String title,
        String shortDescription,
        String detailedDescription,
        Map<String, LocalizedContent> localizations,
        List<String> genres,
        List<String> tags,
        String developer,
        String publisher,
        List<String> screenshots,
        List<String> trailers,
        String iconUrl,
        String coverUrl,
        List<String> supportedLanguages,
        String platform,
        String minimumRequirements,
        String recommendedRequirements,
        List<String> features,
        String supportUrl,
        String privacyPolicyUrl,
        String eulaUrl,
        List<String> salesCountries,
        Map<String, Long> prices
) {
    public StorePageContent {
        localizations = localizations == null ? Map.of() : Map.copyOf(localizations);
        genres = genres == null ? List.of() : List.copyOf(genres);
        tags = tags == null ? List.of() : List.copyOf(tags);
        screenshots = screenshots == null ? List.of() : List.copyOf(screenshots);
        trailers = trailers == null ? List.of() : List.copyOf(trailers);
        supportedLanguages = supportedLanguages == null ? List.of() : List.copyOf(supportedLanguages);
        features = features == null ? List.of() : List.copyOf(features);
        salesCountries = salesCountries == null ? List.of("KR") : List.copyOf(salesCountries);
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    public record LocalizedContent(String title, String shortDescription, String detailedDescription) {
    }
}
