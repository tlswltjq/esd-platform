package com.stove.common.event.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * 컨슈머가 다루기 쉬운 형태로 정규화한 Kafka 레코드.
 * 헤더에서 eventId/eventType 을 꺼내 멱등 판단과 라우팅을 body 파싱 없이 수행한다.
 */
public record EventEnvelope(String eventId, String eventType, String key, String payload) {

    public static EventEnvelope from(ConsumerRecord<String, String> record) {
        return new EventEnvelope(
                header(record, EventHeaders.EVENT_ID),
                header(record, EventHeaders.EVENT_TYPE),
                record.key(),
                record.value());
    }

    public boolean isType(String type) {
        return type.equals(eventType);
    }

    public <T> T payloadAs(ObjectMapper objectMapper, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception e) {
            throw new IllegalStateException("이벤트 역직렬화 실패: " + eventType, e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
