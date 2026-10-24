package com.stove.payment.adapter.trace;

import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.port.PaymentTracing;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;

public class MicrometerPaymentTracing implements PaymentTracing {
    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerPaymentTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public void run(PaymentTraceContext parent, String paymentId, Runnable work) {
        // A scheduler's ambient context must not become this payment's parent or baggage.
        try (CurrentTraceContext.Scope cleared = tracer.currentTraceContext().maybeScope(null)) {
            Span.Builder builder = parent.traceParent() == null
                    ? tracer.spanBuilder().setNoParent()
                    : propagator.extract(Map.of("traceparent", parent.traceParent()), Map::get);
            Span span = builder.name("payment.process").tag("payment.id", paymentId).start();
            try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                work.run();
            } catch (RuntimeException | Error failure) {
                span.error(failure);
                throw failure;
            } finally {
                span.end();
            }
        }
    }
}
