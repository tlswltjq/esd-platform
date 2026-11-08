package com.stove.payment.core.domain;

import java.util.Set;

/**
 * A cancellation POST that PortOne answered with a well-formed typed 4xx (decision 133). The type is any non-blank string
 * the response carried; decision 133 sets no format or length, so it is kept whole. Statuses that keep their own meaning
 * are excluded: 401/404 resume automatically, 403 is its own hold, 408/429 are unconfirmed like a timeout, and the exact
 * 400 INVALID_REQUEST, 409 CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN and 409 IDEMPOTENCY_OUTSTANDING_REQUEST keep their
 * existing classifications.
 */
public record PgCancellationRejection(int status, String type) {
    private static final Set<Integer> OTHER_MEANING = Set.of(401, 403, 404, 408, 429);

    public PgCancellationRejection {
        if (!rejects(status, type)) throw new IllegalArgumentException("Not a formal cancellation rejection");
    }

    /** True only for a typed 4xx that stops automatic POSTs; every other combination keeps its existing classification. */
    public static boolean rejects(int status, String type) {
        if (status < 400 || status > 499 || OTHER_MEANING.contains(status) || type == null || type.isBlank()) return false;
        if (status == 400 && "INVALID_REQUEST".equals(type)) return false;
        return status != 409 || !("CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN".equals(type) || "IDEMPOTENCY_OUTSTANDING_REQUEST".equals(type));
    }
}
