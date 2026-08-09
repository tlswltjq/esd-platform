package com.stove.common.messaging.trace;

import com.stove.common.event.kafka.EventHeaders;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * Micrometer Tracing 으로 현재 스팬의 {@code traceparent} 를 뽑는다.
 *
 * <p>문자열을 손으로 조립하지 않고 {@link Propagator} 에게 맡긴다 — 직렬화 형식은 표준의 소관이고,
 * 우리가 {@code "00-" + traceId + ...} 로 흉내 내면 라이브러리가 형식을 고칠 때 조용히 어긋난다.
 *
 * <p><b>전제</b>: 전파 형식이 W3C 여야 한다(스프링 부트 기본값
 * {@code management.tracing.propagation.type=w3c}). B3 로 바꾸면 주입된 필드에
 * {@code traceparent} 가 없어 {@code null} 이 나오고, <b>Kafka 구간 추적만 조용히 끊긴다</b> —
 * HTTP 구간과 이벤트 처리 자체는 그대로 돈다. 형식을 바꿀 일이 생기면 이 클래스가 같이 바뀌어야 한다.
 */
@RequiredArgsConstructor
public class MicrometerTraceContextCapture implements TraceContextCapture {

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public String captureTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            // 스케줄러·배치처럼 요청에서 시작하지 않은 흐름. 이을 부모가 없으므로 아무것도 싣지 않는다.
            return null;
        }
        Map<String, String> carrier = new HashMap<>(2);
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(EventHeaders.TRACE_PARENT);
    }
}
