package com.stove.common.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.DomainEvent;
import com.stove.common.event.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 리스너 테스트용 {@link ConsumerRecord} 조립기.
 *
 * <p>리스너 검증에 Kafka 는 필요 없다 — 컨테이너가 리스너에 넘겨주는 것은 결국 레코드 한 건이고,
 * 확인하려는 것은 <b>그 레코드를 받았을 때 리스너가 무엇을 하는가</b>이기 때문이다.
 * 브로커를 띄우면 같은 판정에 수십 초가 든다.
 *
 * <p>계약 헤더를 여기서 한 번만 맞춰 둔다. 모듈마다 각자 조립하면
 * 헤더 이름을 틀린 테스트가 "리스너가 무시한다"는 잘못된 결론으로 통과할 수 있다.
 */
public final class EventRecords {

    /** 운영에서 주입되는 것과 같은 설정의 매퍼 */
    public static final ObjectMapper OBJECT_MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private EventRecords() {
    }

    /** 계약을 지킨 정상 레코드. */
    public static ConsumerRecord<String, String> of(String topic, DomainEvent event) {
        try {
            return record(topic, event.partitionKey(), OBJECT_MAPPER.writeValueAsString(event),
                    event.eventId(), event.eventType());
        } catch (Exception e) {
            throw new IllegalStateException("이벤트 직렬화 실패", e);
        }
    }

    /** 이 리스너가 관심 없는 타입. 분기에서 걸러지는지 보려는 용도다. */
    public static ConsumerRecord<String, String> ofUnrelatedType(String topic) {
        return record(topic, "KEY-1", "{}", "EVT-unrelated", "SomethingElseHappened");
    }

    public static ConsumerRecord<String, String> record(
            String topic, String key, String payload, String eventId, String eventType) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, key, payload);
        record.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
