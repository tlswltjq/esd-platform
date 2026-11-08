package com.stove.payment.core.domain;

/**
 * The operator's judgment of who caused a confirmed full cancellation (decision 127). It is recorded with the closure
 * and does not change money, the refund outcome or the execution state.
 */
public enum RefundAttribution {
    /** Our cancellation request for this attempt caused the cancellation. */
    OUR_REQUEST,
    /** The cancellation came from outside this attempt, for example the PG console. */
    EXTERNAL_CANCELLATION,
    /** The operator could not decide. */
    UNDETERMINED;

    /** Exact constant name or null; callers turn null into a bad request. */
    public static RefundAttribution parse(String value) {
        if (value == null) return null;
        for (RefundAttribution attribution : values()) if (attribution.name().equals(value)) return attribution;
        return null;
    }
}
