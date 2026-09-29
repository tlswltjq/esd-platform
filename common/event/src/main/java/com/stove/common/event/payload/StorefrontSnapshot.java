package com.stove.common.event.payload;

import java.util.List;
import java.util.Map;

/** 승인된 상점 revision에서 발행 시점에 고정한 고객용 내용. */
public record StorefrontSnapshot(
        String title, String shortDescription, String detailedDescription,
        Map<String, LocalizedContent> localizations, List<String> genres, List<String> tags,
        String developer, String publisher, List<String> screenshots, List<String> trailers,
        String iconUrl, String coverUrl, List<String> supportedLanguages, String platform,
        String minimumRequirements, String recommendedRequirements, List<String> features,
        String supportUrl, String privacyPolicyUrl, String eulaUrl, List<String> salesCountries
) {
    public record LocalizedContent(String title, String shortDescription, String detailedDescription) {}
}
