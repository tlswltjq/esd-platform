package com.stove.studio.core.domain;

import java.util.Objects;

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
