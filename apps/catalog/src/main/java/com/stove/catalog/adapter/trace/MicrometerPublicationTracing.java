package com.stove.catalog.adapter.trace;

import com.stove.catalog.core.port.PublicationTracing;
import com.stove.common.messaging.trace.TraceContextCapture;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MicrometerPublicationTracing implements PublicationTracing {
    private final Tracer tracer;
    private final Propagator propagator;
    private final TraceContextCapture capture;

    @Override public String capture() { return capture.captureTraceParent(); }

    @Override
    public <T> T call(String traceParent, String requestId, Supplier<T> work) {
        try (CurrentTraceContext.Scope cleared = tracer.currentTraceContext().maybeScope(null)) {
            Span.Builder builder = traceParent == null ? tracer.spanBuilder().setNoParent()
                    : propagator.extract(Map.of("traceparent", traceParent), Map::get);
            Span span = builder.name("catalog.publish").tag("release.request.id", requestId).start();
            try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                return work.get();
            } catch (RuntimeException | Error failure) {
                span.error(failure);
                throw failure;
            } finally {
                span.end();
            }
        }
    }
}
