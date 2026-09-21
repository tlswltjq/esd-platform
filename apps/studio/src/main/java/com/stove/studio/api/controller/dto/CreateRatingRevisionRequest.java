package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateRatingRevisionRequest(
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
        @NotBlank @Pattern(regexp = "ALL|12|15|18") String targetRatingCode,
        @NotBlank @Size(max = 30) String policyVersion,
        @NotNull @NotEmpty Map<String, Object> questionnaire) {
}
