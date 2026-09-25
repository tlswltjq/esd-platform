package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterBuildWebhookRequest(@NotBlank @Size(max = 500) String endpointUrl) {
}
