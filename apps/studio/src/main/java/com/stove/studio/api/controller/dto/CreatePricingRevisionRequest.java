package com.stove.studio.api.controller.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record CreatePricingRevisionRequest(@PositiveOrZero long price) {
}
