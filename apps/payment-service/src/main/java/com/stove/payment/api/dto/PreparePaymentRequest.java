package com.stove.payment.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PreparePaymentRequest(@NotBlank String method) {
}
