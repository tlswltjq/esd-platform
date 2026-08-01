package com.stove.payment.api.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record PreparePaymentRequest(@NotBlank String method) {
}
