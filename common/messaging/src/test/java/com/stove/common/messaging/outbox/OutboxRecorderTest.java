package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.messaging.trace.TraceContextCapture;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 적재. 이 자리가 추적 컨텍스트를 붙잡을 수 있는 <b>마지막 지점</b>이다 —
 * 여기는 아직 요청 스레드이고, 커밋이 끝나면 컨텍스트는 사라진다.
 */
class OutboxRecorderTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 발행 계약만 채운 최소 이벤트. 페이로드 직렬화가 이 테스트의 대상은 아니다. */
    private record PaymentCompleted(String orderNo) implements DomainEvent {
        @Override
        public String eventId() {
            return "EVT-1";
        }

        @Override
        public String eventType() {
            return EventType.PAYMENT_COMPLETED;
        }

        @Override
        public String topic() {
            return Topics.PAYMENT;
        }

        @Override
        public String partitionKey() {
            return orderNo;
        }

        @Override
        public Instant occurredAt() {
            return Instant.EPOCH;
        }
    }

    private OutboxEvent recordWith(TraceContextCapture capture) {
        new OutboxRecorder(repository, objectMapper, capture)
                .record("Payment", "ORD-1", new PaymentCompleted("ORD-1"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("적재 시점의 traceparent 를 이벤트와 같은 행에 남긴다")
    void persistsCapturedTraceParent() {
        OutboxEvent saved = recordWith(() -> TRACE_PARENT);

        assertThat(saved.getTraceParent()).isEqualTo(TRACE_PARENT);
    }

    /**
     * 추적을 구성하지 않은 서비스도 그대로 돌아야 한다.
     * 관측 설정이 이벤트 발행 가능 여부를 좌우하면 관측이 장애 원인이 된다.
     */
    @Test
    @DisplayName("추적이 꺼져 있어도 적재는 그대로 되고 traceparent 만 비어 있다")
    void recordsWithoutTracing() {
        OutboxEvent saved = recordWith(TraceContextCapture.DISABLED);

        assertThat(saved.getTraceParent()).isNull();
        assertThat(saved.getEventId()).isEqualTo("EVT-1");
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("추적 컨텍스트를 붙잡지 못한 경우도 적재를 막지 않는다")
    void recordsWhenCaptureYieldsNothing() {
        OutboxEvent saved = recordWith(() -> null);

        assertThat(saved.getTraceParent()).isNull();
        assertThat(saved.getPayload()).contains("ORD-1");
    }
}
