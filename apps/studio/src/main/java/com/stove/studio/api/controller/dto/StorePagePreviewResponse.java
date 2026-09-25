package com.stove.studio.api.controller.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.studio.core.domain.StorePageContent;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record StorePagePreviewResponse(
        Long revisionId, int revisionNo, StorePageRevisionStatus status, long entityVersion,
        Instant publishedAt, String title, String shortDescription, String detailedDescription,
        Map<String, StorePageContent.LocalizedContent> localizations,
        List<String> genres, List<String> tags, String developer, String publisher,
        List<String> screenshots, List<String> trailers, String iconUrl, String coverUrl,
        List<String> supportedLanguages, String platform, String minimumRequirements,
        String recommendedRequirements, List<String> features, String supportUrl,
        String privacyPolicyUrl, String eulaUrl, List<String> salesCountries, Map<String, Long> prices) {

    public static StorePagePreviewResponse from(StorePageRevision value, ObjectMapper mapper) {
        return new StorePagePreviewResponse(value.getId(), value.getRevisionNo(), value.getStatus(),
                value.getEntityVersion(), value.getPublishedAt(), value.getTitle(), value.getShortDescription(),
                value.getDetailedDescription(), read(mapper, value.getLocalizationsJson(), new TypeReference<>() {}),
                read(mapper, value.getGenresJson(), new TypeReference<>() {}),
                read(mapper, value.getTagsJson(), new TypeReference<>() {}), value.getDeveloper(),
                value.getPublisher(), read(mapper, value.getScreenshotsJson(), new TypeReference<>() {}),
                read(mapper, value.getTrailersJson(), new TypeReference<>() {}), value.getIconUrl(),
                value.getCoverUrl(), read(mapper, value.getSupportedLanguagesJson(), new TypeReference<>() {}),
                value.getPlatform(), value.getMinimumRequirements(), value.getRecommendedRequirements(),
                read(mapper, value.getFeaturesJson(), new TypeReference<>() {}), value.getSupportUrl(),
                value.getPrivacyPolicyUrl(), value.getEulaUrl(),
                read(mapper, value.getSalesCountriesJson(), new TypeReference<>() {}),
                read(mapper, value.getPricesJson(), new TypeReference<>() {}));
    }

    private static <T> T read(ObjectMapper mapper, String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("저장된 상점 페이지 JSON이 올바르지 않습니다.", exception);
        }
    }
}
