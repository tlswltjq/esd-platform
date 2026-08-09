package com.stove.common.kafka.ops;

import com.stove.common.event.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * DLT 에 쌓인 레코드 한 건. {@code payload} 는 담지 않는다({@link DeadEventResponse} 와 같은 이유).
 *
 * @param originalTopic 재투입 대상. {@code DeadLetterPublishingRecoverer} 가 헤더로 남긴다
 * @param exception     무엇 때문에 포기했는지 — 재투입 전에 원인을 고쳤는지 판단하는 근거
 * @param traceParent   실패한 요청의 추적 컨텍스트
 */
public record DltRecordResponse(
        String topic,
        int partition,
        long offset,
        String key,
        String eventId,
        String eventType,
        String originalTopic,
        String exception,
        String traceParent) {

    public static DltRecordResponse from(ConsumerRecord<String, String> record) {
        return new DltRecordResponse(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                header(record, EventHeaders.EVENT_ID),
                header(record, EventHeaders.EVENT_TYPE),
                header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                header(record, EventHeaders.TRACE_PARENT));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
