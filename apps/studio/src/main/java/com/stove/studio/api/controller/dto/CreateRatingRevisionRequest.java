package com.stove.studio.api.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateRatingRevisionRequest(@NotNull @Valid RatingQuestionnaireRequest questionnaire) {
}
