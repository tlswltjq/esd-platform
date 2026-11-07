package com.stove.payment.adapter;

import com.stove.payment.core.domain.ApprovalReviewReason;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.ToLongFunction;

public final class ApprovalReviewMetrics implements MeterBinder {
    private final ToLongFunction<String> counts;
    public ApprovalReviewMetrics(ToLongFunction<String> counts) { this.counts = counts; }
    @Override public void bindTo(MeterRegistry registry) {
        for (String reason : ApprovalReviewReason.ALL)
            Gauge.builder("stove.payment.approval.review.required", this, metrics -> metrics.counts.applyAsLong(reason))
                    .tag("reason", reason).description("Payment approvals requiring operator review").register(registry);
    }
}
