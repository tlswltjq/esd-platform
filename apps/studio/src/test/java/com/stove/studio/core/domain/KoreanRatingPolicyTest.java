package com.stove.studio.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KoreanRatingPolicyTest {

    private final KoreanRatingPolicy policy = new KoreanRatingPolicy();

    @Test
    @DisplayName("내용 강도에 따라 ALL, 12, 15 자체등급을 결정한다")
    void resolvesSelfClassificationAge() {
        assertDecision(questionnaire(ContentSeverity.NONE, ContentSeverity.NONE,
                ContentSeverity.NONE, false, false), "ALL", RatingPath.SELF_CLASSIFICATION);
        assertDecision(questionnaire(ContentSeverity.MILD, ContentSeverity.NONE,
                ContentSeverity.NONE, false, false), "12", RatingPath.SELF_CLASSIFICATION);
        assertDecision(questionnaire(ContentSeverity.NONE, ContentSeverity.NONE,
                ContentSeverity.STRONG, false, false), "15", RatingPath.SELF_CLASSIFICATION);
    }

    @Test
    @DisplayName("강한 폭력성·선정성 또는 현금성 사행 요소는 18세 GRAC 경로로 보낸다")
    void resolvesGracPath() {
        assertDecision(questionnaire(ContentSeverity.STRONG, ContentSeverity.NONE,
                ContentSeverity.NONE, false, false), "18", RatingPath.GRAC);
        assertDecision(questionnaire(ContentSeverity.NONE, ContentSeverity.NONE,
                ContentSeverity.NONE, false, true), "18", RatingPath.GRAC);
    }

    @Test
    @DisplayName("활성 정책은 한국과 현재 정책 버전만 허용한다")
    void validatesActivePolicy() {
        policy.requireActive("KR", KoreanRatingPolicy.VERSION);

        assertThatThrownBy(() -> policy.requireActive("US", KoreanRatingPolicy.VERSION))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireActive("KR", "KR-OLD"))
                .isInstanceOf(BusinessException.class);
    }

    private void assertDecision(RatingQuestionnaire questionnaire, String ratingCode, RatingPath path) {
        KoreanRatingPolicy.Decision decision = policy.evaluate(questionnaire);

        assertThat(decision.country()).isEqualTo("KR");
        assertThat(decision.policyVersion()).isEqualTo("KR-2026-01");
        assertThat(decision.recommendedRatingCode()).isEqualTo(ratingCode);
        assertThat(decision.path()).isEqualTo(path);
    }

    private RatingQuestionnaire questionnaire(ContentSeverity violence, ContentSeverity sexualContent,
                                                ContentSeverity language, boolean drugUse,
                                                boolean cashGambling) {
        return new RatingQuestionnaire(violence, sexualContent, language, drugUse, cashGambling);
    }
}
