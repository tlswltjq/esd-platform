package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateRatingRevisionRequest(@NotNull Map<String, Object> questionnaire) {
}
