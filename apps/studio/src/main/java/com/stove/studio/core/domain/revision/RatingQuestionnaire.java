package com.stove.studio.core.domain.revision;

import java.util.Objects;

/**
 * 등급분류 정책 판단에 필요한 콘텐츠 설문 응답.
 *
 * @param violence 설문에서 응답한 폭력 표현 수위.
 * @param sexualContent 설문에서 응답한 선정적 표현 수위.
 * @param language 설문에서 응답한 언어 표현 수위.
 * @param drugUse 약물 사용 표현의 포함 여부.
 * @param cashGambling 현금 도박 요소의 포함 여부.
 */
public record RatingQuestionnaire(
        ContentSeverity violence,
        ContentSeverity sexualContent,
        ContentSeverity language,
        boolean drugUse,
        boolean cashGambling
) {
    public RatingQuestionnaire {
        Objects.requireNonNull(violence, "violence");
        Objects.requireNonNull(sexualContent, "sexualContent");
        Objects.requireNonNull(language, "language");
    }
}
