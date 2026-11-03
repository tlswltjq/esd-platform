package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.project.WithdrawalCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WithdrawProjectRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String requestId,
        @Size(max = 2000) String reason) {
    public WithdrawalCommand command() { return new WithdrawalCommand(requestId, reason); }
}
