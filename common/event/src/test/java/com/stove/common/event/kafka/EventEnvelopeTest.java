package com.stove.common.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.event.payload.PaymentCompletedEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 봉투(Envelope)는 모든 컨슈머의 첫 관문이다. 여기서 꺼낸 {@code eventId} 가
 * 그대로 멱등 판단 키가 되므로, 이 클래스의 실패 처리 방식이 곧 시스템의 멱등성 한계다.
 */
class EventEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static ConsumerRecord<String, String> record(String payload, String eventId, String eventType) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(Topics.PAYMENT, 0, 0L, "ORD-1", payload);
        if (eventId != null) {
            record.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        if (eventType != null) {
            record.headers().add(EventHeaders.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private static String paymentCompletedJson() throws Exception {
        return MAPPER.writeValueAsString(PaymentCompletedEvent.of(1L, "ORD-1", 42L, 30_000L, "CARD",
                List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 1))));
    }

    @Test
    @DisplayName("헤더에서 eventId 와 eventType 을 꺼낸다 — body 파싱 없이 라우팅한다")
    void extractsHeaders() throws Exception {
        ConsumerRecord<String, String> record =
                record(paymentCompletedJson(), "EVT-1", EventType.PAYMENT_COMPLETED);

        EventEnvelope envelope = EventEnvelope.from(record);

        assertThat(envelope.eventId()).isEqualTo("EVT-1");
        assertThat(envelope.eventType()).isEqualTo(EventType.PAYMENT_COMPLETED);
        assertThat(envelope.key()).isEqualTo("ORD-1");
        assertThat(envelope.isType(EventType.PAYMENT_COMPLETED)).isTrue();
        assertThat(envelope.isType(EventType.PAYMENT_CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("payload 를 지정한 타입으로 역직렬화한다")
    void deserializesPayload() throws Exception {
        EventEnvelope envelope = EventEnvelope.from(
                record(paymentCompletedJson(), "EVT-1", EventType.PAYMENT_COMPLETED));

        PaymentCompletedEvent event = envelope.payloadAs(MAPPER, PaymentCompletedEvent.class);

        assertThat(event.orderNo()).isEqualTo("ORD-1");
        assertThat(event.amount()).isEqualTo(30_000L);
        assertThat(event.lines()).hasSize(1);
    }

    @Test
    @DisplayName("깨진 payload 는 IllegalStateException 으로 감싼다")
    void wrapsDeserializationFailure() {
        EventEnvelope envelope = EventEnvelope.from(
                record("{not json", "EVT-1", EventType.PAYMENT_COMPLETED));

        assertThatThrownBy(() -> envelope.payloadAs(MAPPER, PaymentCompletedEvent.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("역직렬화 실패");
    }

    @Test
    @DisplayName("현재 동작: eventId 헤더가 없으면 null 을 그대로 돌려준다")
    void missingEventIdBecomesNull() throws Exception {
        EventEnvelope envelope = EventEnvelope.from(
                record(paymentCompletedJson(), null, EventType.PAYMENT_COMPLETED));

        // 이 null 은 그대로 ProcessedEventGuard 로 흘러가 event_id NOT NULL 제약에 걸린다.
        // 즉 실패 지점이 '봉투'가 아니라 '트랜잭션 한복판'이 된다.
        assertThat(envelope.eventId()).isNull();
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-004] eventId 없는 레코드는 봉투 단계에서 거부되어야 한다")
    void shouldRejectRecordWithoutEventId() throws Exception {
        ConsumerRecord<String, String> broken =
                record(paymentCompletedJson(), null, EventType.PAYMENT_COMPLETED);

        // 기대: 계약 위반을 입구에서 끊어 무엇이 잘못됐는지 즉시 드러낸다.
        // 실제: null 을 반환해 통과시키므로 DB 제약 위반으로 뒤늦게 터지고,
        //       그 레코드는 오프셋이 커밋되지 않아 파티션 전체를 막는다(poison message).
        assertThatThrownBy(() -> EventEnvelope.from(broken))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-004] eventType 없는 레코드도 봉투 단계에서 거부되어야 한다")
    void shouldRejectRecordWithoutEventType() throws Exception {
        ConsumerRecord<String, String> broken = record(paymentCompletedJson(), "EVT-1", null);

        // eventType 이 null 이면 isType() 이 전부 false 라 리스너가 조용히 아무것도 안 한다.
        // 이벤트가 유실됐는데 로그에도 흔적이 남지 않는 것이 더 나쁘다.
        assertThatThrownBy(() -> EventEnvelope.from(broken))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("eventType 이 null 이면 어떤 분기도 타지 않는다 — 조용한 유실 경로")
    void nullEventTypeMatchesNothing() throws Exception {
        EventEnvelope envelope = EventEnvelope.from(record(paymentCompletedJson(), "EVT-1", null));

        assertThat(envelope.isType(EventType.PAYMENT_COMPLETED)).isFalse();
        assertThat(envelope.isType(EventType.PAYMENT_CANCELLED)).isFalse();
    }
}
