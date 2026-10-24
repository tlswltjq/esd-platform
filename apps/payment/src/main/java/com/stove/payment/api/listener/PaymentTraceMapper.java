package com.stove.payment.api.listener;

import com.stove.common.kafka.EventHeaders;
import com.stove.payment.core.domain.PaymentTraceContext;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

@Slf4j
public final class PaymentTraceMapper {
    private PaymentTraceMapper() {}

    public static PaymentTraceContext from(Headers headers) {
        Iterator<Header> parents = headers.headers(EventHeaders.TRACE_PARENT).iterator();
        if (!parents.hasNext()) return PaymentTraceContext.NONE;
        byte[] value = parents.next().value();
        if (parents.hasNext()) return ignored("DUPLICATE");
        if (value == null || value.length > 64) return ignored("INVALID_LENGTH");
        try {
            return new PaymentTraceContext(new String(value, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            return ignored("INVALID_FORMAT");
        }
    }

    private static PaymentTraceContext ignored(String reason) {
        log.warn("Payment 추적 헤더 무시 reason={}", reason);
        return PaymentTraceContext.NONE;
    }
}
