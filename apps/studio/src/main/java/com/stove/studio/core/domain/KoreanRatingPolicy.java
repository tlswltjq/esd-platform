package com.stove.studio.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import org.springframework.stereotype.Component;

/** STOVE BASIC 한국 출시의 설문 기반 등급 경로를 결정하는 버전 고정 정책. */
@Component
public class KoreanRatingPolicy {

    public static final String COUNTRY = "KR";
    public static final String VERSION = "KR-2026-01";

    public Decision evaluate(RatingQuestionnaire questionnaire) {
        if (questionnaire.cashGambling()
                || questionnaire.violence() == ContentSeverity.STRONG
                || questionnaire.sexualContent() == ContentSeverity.STRONG) {
            return new Decision(COUNTRY, VERSION, "18", RatingPath.GRAC);
        }
        if (questionnaire.drugUse() || questionnaire.language() == ContentSeverity.STRONG) {
            return new Decision(COUNTRY, VERSION, "15", RatingPath.SELF_CLASSIFICATION);
        }
        if (questionnaire.violence() == ContentSeverity.MILD
                || questionnaire.sexualContent() == ContentSeverity.MILD
                || questionnaire.language() == ContentSeverity.MILD) {
            return new Decision(COUNTRY, VERSION, "12", RatingPath.SELF_CLASSIFICATION);
        }
        return new Decision(COUNTRY, VERSION, "ALL", RatingPath.SELF_CLASSIFICATION);
    }

    public void requireActive(String country, String policyVersion) {
        if (!COUNTRY.equals(country)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "P0 등급 정책은 한국(KR) 출시만 지원합니다.");
        }
        if (!VERSION.equals(policyVersion)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지원하지 않거나 만료된 등급 정책 버전입니다: " + policyVersion);
        }
    }

    public record Decision(String country, String policyVersion,
                           String recommendedRatingCode, RatingPath path) {
    }
}
