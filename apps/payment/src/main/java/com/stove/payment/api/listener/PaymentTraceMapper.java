package com.stove.payment.api.listener;

import com.stove.common.kafka.EventHeaders;
import com.stove.payment.core.domain.PaymentTraceContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PaymentTraceMapper {
    private PaymentTraceMapper() {}

    /** @param traceParents 수신 메시지의 {@link EventHeaders#TRACE_PARENT} 값 전부({@code InboundMessage#headerValues}). */
    public static PaymentTraceContext from(List<String> traceParents) {
        if (traceParents.isEmpty()) return PaymentTraceContext.NONE;
        if (traceParents.size() > 1) return ignored("DUPLICATE");
        String value = traceParents.get(0);
        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 64) return ignored("INVALID_LENGTH");
        try {
            return new PaymentTraceContext(value);
        } catch (IllegalArgumentException invalid) {
            return ignored("INVALID_FORMAT");
        }
    }

    private static PaymentTraceContext ignored(String reason) {
        log.warn("Payment 추적 헤더 무시 reason={}", reason);
        return PaymentTraceContext.NONE;
    }
}
