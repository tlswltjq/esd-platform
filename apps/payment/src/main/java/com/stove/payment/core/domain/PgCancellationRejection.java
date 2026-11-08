package com.stove.payment.core.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A cancellation POST that PortOne answered with a well-formed typed 4xx (decision 133). Statuses that keep their own
 * meaning are excluded: 401/404 resume automatically, 403 is its own hold, 408/429 are unconfirmed like a timeout, and the
 * exact 400 INVALID_REQUEST, 409 CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN and 409 IDEMPOTENCY_OUTSTANDING_REQUEST keep their
 * existing classifications.
 */
public record PgCancellationRejection(int status, String type) {
    public static final int TYPE_LENGTH = 64;
    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0," + (TYPE_LENGTH - 1) + "}");
    private static final Set<Integer> OTHER_MEANING = Set.of(401, 403, 404, 408, 429);

    public PgCancellationRejection {
        if (!rejects(status, type)) throw new IllegalArgumentException("Not a formal cancellation rejection");
    }

    /** True only for a typed 4xx that stops automatic POSTs; every other combination keeps its existing classification. */
    public static boolean rejects(int status, String type) {
        if (status < 400 || status > 499 || OTHER_MEANING.contains(status) || type == null || !TYPE.matcher(type).matches()) return false;
        if (status == 400 && "INVALID_REQUEST".equals(type)) return false;
        return status != 409 || !("CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN".equals(type) || "IDEMPOTENCY_OUTSTANDING_REQUEST".equals(type));
    }
}
