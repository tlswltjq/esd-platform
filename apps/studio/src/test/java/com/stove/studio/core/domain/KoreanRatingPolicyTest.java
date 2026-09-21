package com.stove.studio.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class KoreanRatingPolicyTest {

    private final KoreanRatingPolicy policy = new KoreanRatingPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "12", "15"})
    @DisplayName("한국의 전체·12·15세 목표 등급은 자체등급분류 경로로 결정한다")
    void classifiesSelfRating(String targetRatingCode) {
        KoreanRatingPolicy.RatingClassification result = policy.classify(
                "KR", targetRatingCode, KoreanRatingPolicy.VERSION, questionnaire(false, false));

        assertThat(result.path()).isEqualTo(RatingPath.SELF_CLASSIFICATION);
        assertThat(result.targetRatingCode()).isEqualTo(targetRatingCode);
    }

    @Test
    @DisplayName("한국의 18세 목표 등급은 콘텐츠 응답과 함께 GRAC 경로로 결정한다")
    void classifiesGracRating() {
        KoreanRatingPolicy.RatingClassification result = policy.classify(
                "KR", "18", KoreanRatingPolicy.VERSION, questionnaire(true, false));

        assertThat(result.path()).isEqualTo(RatingPath.GRAC);
    }

    @Test
    @DisplayName("성인 콘텐츠를 낮은 목표 등급으로 제출할 수 없다")
    void rejectsContradictingQuestionnaire() {
        assertThatThrownBy(() -> policy.classify(
                "KR", "15", KoreanRatingPolicy.VERSION, questionnaire(false, true)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("지역, 정책 버전, 필수 설문 응답을 모두 검증한다")
    void rejectsUnsupportedPolicyContext() {
        assertThatThrownBy(() -> policy.classify(
                "US", "ALL", KoreanRatingPolicy.VERSION, questionnaire(false, false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.classify(
                "KR", "ALL", "KR-OLD", questionnaire(false, false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.classify(
                "KR", "ALL", KoreanRatingPolicy.VERSION, Map.of("adultContent", false)))
                .isInstanceOf(BusinessException.class);
    }

    private Map<String, Object> questionnaire(boolean adultContent, boolean cashGambling) {
        return Map.of("adultContent", adultContent, "cashGambling", cashGambling);
    }
}
