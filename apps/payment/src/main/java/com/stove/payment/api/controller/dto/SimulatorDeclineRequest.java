package com.stove.payment.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SimulatorDeclineRequest(
        @NotBlank @Size(max = 50) String reasonCode,
        @Size(max = 200) String reason
) {}
