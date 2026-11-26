package com.stove.bench.messaging.app.api.listener;

import com.stove.bench.messaging.app.core.domain.BenchMessage;
import com.stove.bench.messaging.app.core.domain.FailurePlan;
import com.stove.common.contract.EventEnvelope;
import com.stove.common.contract.EventHeaders;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** 시험 메시지의 봉투·헤더를 검증해 내부 입력으로 바꾼다(AGENTS 3절). 형식이 틀리면 부수효과 전에 거절한다. */
public final class BenchMessageMapper {

    private BenchMessageMapper() {
    }

    public static BenchMessage from(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = EventEnvelope.from(record);
        if (!envelope.isType(BenchMessage.EVENT_TYPE)) {
            throw new IllegalArgumentException("시험 메시지가 아니다 eventType=" + envelope.eventType());
        }
        String sentAt = EventHeaders.lastValue(record.headers(), BenchMessage.SENT_AT_HEADER);
        if (sentAt == null) {
            throw new IllegalArgumentException(BenchMessage.SENT_AT_HEADER + " 헤더가 없다 eventId=" + envelope.eventId());
        }
        long sentAtMicros;
        try {
            sentAtMicros = Long.parseLong(sentAt);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(BenchMessage.SENT_AT_HEADER + " 형식이 아니다: " + sentAt, e);
        }
        FailurePlan failure = FailurePlan.parse(EventHeaders.lastValue(record.headers(), FailurePlan.HEADER));
        int payloadBytes = record.value() == null ? 0 : record.value().getBytes(StandardCharsets.UTF_8).length;
        return new BenchMessage(envelope.eventId(), envelope.key(), sentAtMicros, failure, payloadBytes);
    }
}
