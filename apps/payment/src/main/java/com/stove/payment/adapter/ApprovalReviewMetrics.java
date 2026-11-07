package com.stove.payment.adapter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import java.util.Map;
import java.util.function.Supplier;

public final class ApprovalReviewMetrics implements MeterBinder {
    private final Supplier<Map<String, Long>> counts;
    private final String application;
    public ApprovalReviewMetrics(Supplier<Map<String, Long>> counts, String application) { this.counts = counts; this.application = application; }
    @Override public void bindTo(MeterRegistry registry) {
        if (registry instanceof PrometheusMeterRegistry prometheus) {
            GaugeWithCallback.builder().name("stove_payment_approval_review_required")
                    .help("Payment approvals requiring operator review").labelNames("application", "reason")
                    .callback(callback -> counts.get().forEach((reason, total) -> callback.call(total, application, reason)))
                    .register(prometheus.getPrometheusRegistry());
        }
    }
}
