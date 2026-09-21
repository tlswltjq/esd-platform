package com.stove.studio.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** STOVE BASIC 한국 출시의 등급 경로를 결정하는 버전 고정 정책. */
@Component
public class KoreanRatingPolicy {

    public static final String COUNTRY = "KR";
    public static final String VERSION = "KR-2026-01";
    private static final Set<String> SUPPORTED_RATINGS = Set.of("ALL", "12", "15", "18");

    public RatingClassification classify(String country, String targetRatingCode, String policyVersion,
                                         Map<String, Object> questionnaire) {
        requireActive(country, policyVersion);
        if (!SUPPORTED_RATINGS.contains(targetRatingCode)) {
            throw invalid("지원하지 않는 목표 등급입니다: " + targetRatingCode);
        }

        boolean adultContent = requiredBoolean(questionnaire, "adultContent");
        boolean cashGambling = requiredBoolean(questionnaire, "cashGambling");
        boolean requiresAdultRating = adultContent || cashGambling;
        if (requiresAdultRating && !"18".equals(targetRatingCode)) {
            throw invalid("성인 콘텐츠 또는 현금성 사행 요소가 있으면 목표 등급은 18이어야 합니다.");
        }

        RatingPath path = "18".equals(targetRatingCode)
                ? RatingPath.GRAC
                : RatingPath.SELF_CLASSIFICATION;
        return new RatingClassification(country, targetRatingCode, policyVersion, path);
    }

    public void requireActive(String country, String policyVersion) {
        if (!COUNTRY.equals(country)) {
            throw invalid("P0 등급 정책은 한국(KR) 출시만 지원합니다.");
        }
        if (!VERSION.equals(policyVersion)) {
            throw invalid("지원하지 않거나 만료된 등급 정책 버전입니다: " + policyVersion);
        }
    }

    private boolean requiredBoolean(Map<String, Object> questionnaire, String key) {
        Object answer = questionnaire.get(key);
        if (!(answer instanceof Boolean value)) {
            throw invalid("등급 설문에 boolean 응답이 필요합니다: " + key);
        }
        return value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    public record RatingClassification(String country, String targetRatingCode,
                                       String policyVersion, RatingPath path) {
    }
}
