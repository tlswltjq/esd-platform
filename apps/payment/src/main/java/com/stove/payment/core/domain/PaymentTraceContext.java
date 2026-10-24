package com.stove.payment.core.domain;

import java.util.regex.Pattern;

/** Validated, optional parent metadata; independent of the tracing implementation. */
public record PaymentTraceContext(String traceParent) {
    private static final Pattern VERSION_ZERO = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    public static final PaymentTraceContext NONE = new PaymentTraceContext(null);

    public PaymentTraceContext {
        requireTraceParent(traceParent);
    }

    private static void requireTraceParent(String value) {
        if (value == null) return;
        if (!VERSION_ZERO.matcher(value).matches()
                || value.substring(3, 35).equals("00000000000000000000000000000000")
                || value.substring(36, 52).equals("0000000000000000")) {
            throw new IllegalArgumentException("Invalid payment trace parent");
        }
    }
}
