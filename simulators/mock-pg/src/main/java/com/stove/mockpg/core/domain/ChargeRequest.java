package com.stove.mockpg.core.domain;

import java.util.UUID;

public record ChargeRequest(String paymentId, String orderNo, long amount, String currency) {
    public ChargeRequest {
        if (paymentId == null || paymentId.isBlank() || paymentId.length() > 64
                || orderNo == null || !validOrder(orderNo) || amount <= 0 || !"KRW".equals(currency)) {
            throw new IllegalArgumentException("Invalid charge request");
        }
    }

    private static boolean validOrder(String value) {
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
