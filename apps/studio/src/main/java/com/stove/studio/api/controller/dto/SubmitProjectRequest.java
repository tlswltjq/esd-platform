package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.project.SubmissionCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record SubmitProjectRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String requestId,
        @Positive long contentVersion) {
    public SubmissionCommand command() { return new SubmissionCommand(requestId, contentVersion); }
}
