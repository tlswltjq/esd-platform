package com.stove.common.messaging.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 추적 컨텍스트를 문자열로 붙잡는 부분.
 *
 * <p>진짜 Tracer 를 쓰지 않는다 — 검증 대상은 OpenTelemetry 구현이 아니라
 * <b>전파기가 내놓은 필드 중 무엇을 골라 들고 나오는가</b>이기 때문이다.
 */
class MicrometerTraceContextCaptureTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final Tracer tracer = mock(Tracer.class);
    private final Propagator propagator = mock(Propagator.class);
    private final MicrometerTraceContextCapture capture =
            new MicrometerTraceContextCapture(tracer, propagator);

    /** 전파기가 주어진 필드들을 실어 주는 상황을 만든다. */
    @SuppressWarnings("unchecked")
    private TraceContext propagatorInjects(Map<String, String> fields) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);

        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            fields.forEach((key, value) -> setter.set(carrier, key, value));
            return null;
        }).when(propagator).inject(eq(context), any(), any());

        return context;
    }

    @Test
    @DisplayName("현재 스팬의 traceparent 를 전파기가 직렬화한 그대로 돌려준다")
    void capturesTraceParent() {
        propagatorInjects(Map.of(
                "traceparent", TRACE_PARENT,
                "tracestate", "vendor=abc"));

        assertThat(capture.captureTraceParent()).isEqualTo(TRACE_PARENT);
    }

    @Test
    @DisplayName("진행 중인 스팬이 없으면 아무것도 붙잡지 않는다 — 이을 부모가 없다")
    void capturesNothingWithoutSpan() {
        when(tracer.currentSpan()).thenReturn(null);

        assertThat(capture.captureTraceParent()).isNull();
    }

    /**
     * 전파 형식을 W3C 가 아닌 것으로 바꾸면 이 클래스가 값을 못 찾는다.
     * <b>그때 무엇이 망가지는지</b>를 못 박아 둔다 — Kafka 구간 추적만 끊기고,
     * 적재도 발행도 그대로 된다(traceparent 가 null 이면 릴레이가 헤더를 생략한다).
     */
    @Test
    @DisplayName("W3C 가 아닌 전파 형식이면 붙잡지 못하고 null 로 떨어진다")
    void capturesNothingForNonW3cPropagation() {
        propagatorInjects(Map.of(
                "X-B3-TraceId", "4bf92f3577b34da6a3ce929d0e0e4736",
                "X-B3-SpanId", "00f067aa0ba902b7"));

        assertThat(capture.captureTraceParent()).isNull();
    }
}
