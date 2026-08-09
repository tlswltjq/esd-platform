package com.stove.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 지금 요청의 traceId 를 응답 헤더로 돌려준다.
 *
 * <p>여기 있던 {@code CorrelationIdFilter} 를 대신한다. 그 필터는 세 가지 일을 했는데
 * 이제 셋 중 둘을 추적 라이브러리가 더 잘 한다:
 *
 * <ul>
 *   <li><b>식별자 생성/이어받기</b> — {@code traceparent}(W3C 표준)로 대체됐다.
 *       직접 만든 {@code X-Correlation-Id} 는 우리끼리만 통하지만 표준은 외부 시스템과도 통한다.</li>
 *   <li><b>MDC 적재</b> — Micrometer Tracing 이 {@code traceId}/{@code spanId} 를 자동으로 넣고
 *       스코프가 닫힐 때 지운다. 손으로 {@code MDC.remove} 를 부르며 누수를 걱정할 일이 없어졌다.</li>
 *   <li><b>응답 헤더로 돌려주기</b> — 이것만 남았다. 추적 라이브러리는 하지 않는 일이고,
 *       "장애 문의에 이 값을 같이 달라"고 말할 창구가 있어야 하므로 유지한다.</li>
 * </ul>
 *
 * <p>헤더 이름은 {@code X-Correlation-Id} 그대로 둔다 — 밖에서 보이는 계약이라
 * 내부 구현을 바꿨다고 따라 바꿀 이유가 없다. <b>값의 출처만 traceId 로 일원화됐다.</b>
 *
 * <p>순서는 옵저베이션 필터({@code HIGHEST_PRECEDENCE + 1}) <b>안쪽</b>이어야 한다.
 * 바깥이면 스팬이 아직 열리지 않아 {@code currentSpan()} 이 비어 있다.
 * 또 헤더는 {@code doFilter} <b>전에</b> 넣는다 — 응답이 커밋된 뒤에는 헤더가 나가지 않는다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class TraceIdResponseFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    private final Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = currentTraceId();
        if (traceId != null) {
            response.setHeader(HEADER, traceId);
        }
        chain.doFilter(request, response);
    }

    /**
     * 값이 있을 때만 돌려준다. <b>{@code null} 검사만으로는 부족하다</b> —
     * 추적이 꺼진 구성의 {@code Tracer.NOOP} 은 {@code null} 이 아니라 빈 문자열 traceId 를 가진
     * 스팬을 내놓는다. 그대로 실으면 헤더가 빈 값으로 나가는데, 헤더가 없는 것보다 나쁘다:
     * 받는 쪽은 값이 있다고 보고 빈 문자열로 로그를 뒤지게 된다.
     */
    private String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        String traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }
}
