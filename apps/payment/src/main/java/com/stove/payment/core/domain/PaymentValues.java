package com.stove.payment.core.domain;

import java.time.Instant;

/** Shared value constraints for payment records and supplier values. */
final class PaymentValues {
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private PaymentValues() {}

    static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    static boolean uuid(String value) {
        return value != null && value.matches(UUID_PATTERN);
    }

    static boolean microsecond(Instant value) {
        return value != null && value.getNano() % 1000 == 0;
    }
}
