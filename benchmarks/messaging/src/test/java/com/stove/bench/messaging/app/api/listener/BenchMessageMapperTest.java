package com.stove.bench.messaging.app.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.bench.messaging.app.core.domain.BenchMessage;
import com.stove.bench.messaging.app.core.domain.FailurePlan;
import com.stove.common.contract.EventHeaders;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class BenchMessageMapperTest {

    @Test
    void 헤더와_본문을_내부_입력으로_바꾼다() {
        ConsumerRecord<String, String> record = record("BenchMessage", "1700000000000000", "transient:1", "{\"a\":\"가\"}");

        BenchMessage message = BenchMessageMapper.from(record);

        assertThat(message.eventId()).isEqualTo("e-1");
        assertThat(message.key()).isEqualTo("k-00001");
        assertThat(message.sentAtMicros()).isEqualTo(1_700_000_000_000_000L);
        assertThat(message.failure()).isEqualTo(new FailurePlan(1, false));
        // 문자 수(9)가 아니라 UTF-8 바이트 수다: ASCII 8 + 한글 한 글자 3.
        assertThat(message.payloadBytes()).isEqualTo(11);
    }

    @Test
    void 시험_메시지가_아니거나_발행_시각이_없으면_거절한다() {
        assertThatThrownBy(() -> BenchMessageMapper.from(record("OrderPaid", "1", null, "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchMessageMapper.from(record("BenchMessage", null, null, "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchMessageMapper.from(record("BenchMessage", "soon", null, "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ConsumerRecord<String, String> record(String type, String sentAt, String fail, String body) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("bench.messages.v1", 1, 7L, "k-00001", body);
        record.headers().add(EventHeaders.EVENT_ID, "e-1".getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        if (sentAt != null) {
            record.headers().add(BenchMessage.SENT_AT_HEADER, sentAt.getBytes(StandardCharsets.UTF_8));
        }
        if (fail != null) {
            record.headers().add(FailurePlan.HEADER, fail.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
