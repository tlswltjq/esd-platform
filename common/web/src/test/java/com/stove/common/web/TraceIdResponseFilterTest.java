package com.stove.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 응답으로 traceId 를 돌려주는 필터. 여기 있던 {@code CorrelationIdFilter} 의 후임이고,
 * 그 필터는 무테스트였다(testing.md 6.3).
 */
class TraceIdResponseFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    private static Tracer tracerWithSpan(String traceId) {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(traceId);
        when(span.context()).thenReturn(context);
        when(tracer.currentSpan()).thenReturn(span);
        return tracer;
    }

    @Test
    @DisplayName("현재 트레이스의 ID를 X-Correlation-Id 로 돌려준다")
    void echoesTraceId() throws Exception {
        new TraceIdResponseFilter(tracerWithSpan(TRACE_ID)).doFilter(request, response, chain);

        assertThat(response.getHeader(TraceIdResponseFilter.HEADER)).isEqualTo(TRACE_ID);
    }

    /**
     * 헤더는 {@code doFilter} <b>전에</b> 넣어야 한다. 응답이 커밋된 뒤에 넣으면 조용히 버려진다 —
     * 컨트롤러가 스트리밍이나 조기 flush 를 하는 순간 헤더가 사라지는 종류의 결함이다.
     */
    @Test
    @DisplayName("응답이 커밋된 뒤에도 헤더가 남아 있다 — 체인보다 먼저 넣기 때문")
    void setsHeaderBeforeChain() throws Exception {
        FilterChain committing = (req, res) -> ((MockHttpServletResponse) res).setCommitted(true);

        new TraceIdResponseFilter(tracerWithSpan(TRACE_ID)).doFilter(request, response, committing);

        assertThat(response.getHeader(TraceIdResponseFilter.HEADER)).isEqualTo(TRACE_ID);
    }

    /**
     * 추적을 구성하지 않은 실행에서도 요청은 그대로 처리돼야 한다.
     * 관측 장치가 요청 경로를 끊으면 관측이 장애 원인이 된다.
     */
    @Test
    @DisplayName("추적이 없으면 헤더 없이 그냥 통과시킨다")
    void passesThroughWithoutTracing() throws Exception {
        new TraceIdResponseFilter(Tracer.NOOP).doFilter(request, response, chain);

        assertThat(response.getHeader(TraceIdResponseFilter.HEADER)).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
