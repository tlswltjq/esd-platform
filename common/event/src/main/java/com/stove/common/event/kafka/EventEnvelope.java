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

    /**
     * 레코드를 봉투로 정규화한다. 계약 헤더가 없으면 <b>여기서 끊는다.</b>
     *
     * <p>통과시키면 {@code eventId} 가 null 인 채로 멱등 가드까지 흘러가 비즈니스 로직이
     * 실행된 뒤 DB 제약에서 터진다 — 실패 지점이 입구가 아니라 트랜잭션 한복판이 된다.
     * {@code eventType} 이 null 이면 더 나쁘다. 모든 {@code isType()} 이 false 라
     * 리스너가 아무 분기도 타지 않고 정상 리턴하므로 <b>로그에도 흔적이 남지 않는다.</b>
     *
     * <p>판단할 수 없는 입력은 부수효과 이전에 거부한다. 이후 처리는 컨슈머의 에러 핸들러
     * 정책이 맡는다(재시도 소진 후 기록하고 건너뛴다).
     */
    public static EventEnvelope from(ConsumerRecord<String, String> record) {
        String eventId = header(record, EventHeaders.EVENT_ID);
        String eventType = header(record, EventHeaders.EVENT_TYPE);
        requireHeader(eventId, EventHeaders.EVENT_ID, record);
        requireHeader(eventType, EventHeaders.EVENT_TYPE, record);

        return new EventEnvelope(eventId, eventType, record.key(), record.value());
    }

    private static void requireHeader(String value, String name, ConsumerRecord<String, String> record) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "이벤트 계약 위반: %s 헤더가 없다 topic=%s partition=%d offset=%d key=%s"
                            .formatted(name, record.topic(), record.partition(), record.offset(), record.key()));
        }
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
