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
 * 지금 요청의 traceId 를 응답 헤더로 돌려준다. 헤더 이름({@code X-Correlation-Id})은 계약이라
 * 그대로 두고 <b>값의 출처만 traceId 로 일원화됐다.</b>
 *
 * <p><b>순서는 옵저베이션 필터 안쪽이어야 한다</b>(바깥이면 스팬이 아직 없다).
 * 헤더는 {@code doFilter} <b>전에</b> 넣는다 — 응답이 커밋된 뒤에는 나가지 않는다.
 * docs/code-notes.md
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
     * {@code Tracer.NOOP} 은 빈 문자열 traceId 를 내놓는다. docs/code-notes.md
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
