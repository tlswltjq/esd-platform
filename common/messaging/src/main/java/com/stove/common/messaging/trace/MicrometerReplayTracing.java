package com.stove.common.messaging.trace;

import com.stove.common.contract.EventHeaders;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;

/** 원래 {@code traceparent} 를 부모로 하는 span 을 열고, 그 안에서 재투입 처리를 돌린 뒤 반드시 닫는다. */
public class MicrometerReplayTracing implements ReplayTracing {

    static final String SPAN_NAME = "quarantine.replay";

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerReplayTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public void runWithin(String traceParent, Runnable work) {
        Span span = traceParent == null || traceParent.isBlank()
                ? tracer.nextSpan().name(SPAN_NAME).start()
                : propagator.extract(Map.of(EventHeaders.TRACE_PARENT, traceParent), Map::get).name(SPAN_NAME).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            work.run();
        } finally {
            span.end();
        }
    }
}
