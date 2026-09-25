package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.ReleaseChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TesterGrantRequest(@NotBlank @Size(max = 100) String testerSubject,
                                 @NotNull ReleaseChannel channel) {
}
