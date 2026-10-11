package com.stove.studio.core.domain.revision;

import java.util.List;
import java.util.Map;

/**
 * 상점 소개 판본을 만들기 위한 다국어 콘텐츠와 판매 정보.
 *
 * @param title 게임 또는 상점 페이지에 표시할 제목.
 * @param shortDescription 상점에 노출할 짧은 소개.
 * @param detailedDescription 상점 페이지에 표시할 상세 소개.
 * @param localizations 언어 코드별 제목과 소개 자료.
 * @param genres 상품에 지정한 장르 목록.
 * @param tags 검색과 분류에 사용하는 태그 목록.
 * @param developer 상점에 표시할 개발사 이름.
 * @param publisher 상점에 표시할 배급사 이름.
 * @param screenshots 상점에 표시할 스크린샷 URL 목록.
 * @param trailers 상점에 표시할 소개 영상 URL 목록.
 * @param iconUrl 상품 아이콘 이미지 URL.
 * @param coverUrl 상품 대표 표지 이미지 URL.
 * @param supportedLanguages 게임이 지원하는 언어 목록.
 * @param platform 빌드가 실행될 대상 플랫폼.
 * @param minimumRequirements 게임 실행에 필요한 최소 환경.
 * @param recommendedRequirements 게임 실행을 권장하는 환경.
 * @param features 상점에 표시할 지원 기능 목록.
 * @param supportUrl 고객 지원 페이지 URL.
 * @param privacyPolicyUrl 개인정보 처리방침 URL.
 * @param eulaUrl 최종 사용자 이용 계약 URL.
 * @param salesCountries 판매를 허용하는 국가 코드 목록.
 * @param prices 국가별 가격 자료.
 */
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

    /**
     * 특정 언어로 작성한 상점 제목과 소개.
     *
     * @param title 게임 또는 상점 페이지에 표시할 제목.
     * @param shortDescription 상점에 노출할 짧은 소개.
     * @param detailedDescription 상점 페이지에 표시할 상세 소개.
     */
    public record LocalizedContent(String title, String shortDescription, String detailedDescription) {
    }
}
