package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.ContentSeverity;
import com.stove.studio.core.domain.RatingQuestionnaire;
import jakarta.validation.constraints.NotNull;

public record RatingQuestionnaireRequest(
        @NotNull ContentSeverity violence,
        @NotNull ContentSeverity sexualContent,
        @NotNull ContentSeverity language,
        @NotNull Boolean drugUse,
        @NotNull Boolean cashGambling
) {
    public RatingQuestionnaire toDomain() {
        return new RatingQuestionnaire(violence, sexualContent, language, drugUse, cashGambling);
    }
}
