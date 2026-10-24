package com.stove.payment.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.kafka.EventHeaders;
import com.stove.payment.core.domain.PaymentTraceContext;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentTraceMapperTest {
    private static final String PREFIX = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-";

    @ParameterizedTest
    @ValueSource(strings = {"00", "01", "03"})
    void preservesValidParentAndSamplingFlags(String flags) {
        assertThat(PaymentTraceMapper.from(headers(PREFIX + flags)).traceParent()).isEqualTo(PREFIX + flags);
    }

    @Test void absentNullAndDuplicateHeadersDoNotRejectBusinessInput() {
        assertThat(PaymentTraceMapper.from(new RecordHeaders())).isEqualTo(PaymentTraceContext.NONE);
        RecordHeaders missingValue = new RecordHeaders();
        missingValue.add(EventHeaders.TRACE_PARENT, null);
        assertThat(PaymentTraceMapper.from(missingValue)).isEqualTo(PaymentTraceContext.NONE);
        RecordHeaders duplicate = headers(PREFIX + "01");
        duplicate.add(EventHeaders.TRACE_PARENT, (PREFIX + "00").getBytes(StandardCharsets.UTF_8));
        assertThat(PaymentTraceMapper.from(duplicate)).isEqualTo(PaymentTraceContext.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01",
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
            "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra"})
    void invalidMetadataIsIgnoredAtTheBoundaryButRejectedByTheInternalValue(String value) {
        assertThat(PaymentTraceMapper.from(headers(value))).isEqualTo(PaymentTraceContext.NONE);
        assertThatThrownBy(() -> new PaymentTraceContext(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oversizedHeaderIsIgnored() {
        assertThat(PaymentTraceMapper.from(headers("a".repeat(1000)))).isEqualTo(PaymentTraceContext.NONE);
    }

    private static RecordHeaders headers(String value) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(EventHeaders.TRACE_PARENT, value.getBytes(StandardCharsets.UTF_8));
        return headers;
    }
}
