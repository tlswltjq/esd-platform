package com.stove.common.messaging.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 발행을 포기한 이벤트를 되살리는 운영 경로.
 *
 * <p>{@code requeue()} 자체는 처음부터 있었고 {@code OutboxEventTest} 가 검증하고 있었다.
 * 없던 것은 <b>그것을 부를 방법</b>이었다 — 프로덕션 코드에 호출처가 하나도 없어서
 * 되살리려면 운영자가 DB 에 직접 UPDATE 를 쳐야 했다. 여기서 보는 것은 그 문이 열렸는가다.
 */
class OutboxOpsServiceTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxOpsService service = new OutboxOpsService(repository);

    private static OutboxEvent deadEvent(String eventId) {
        OutboxEvent event = OutboxEvent.pending(eventId, "Payment", "ORD-1",
                EventType.PAYMENT_COMPLETED, Topics.PAYMENT, "ORD-1", "{}", TRACE_PARENT);
        event.markFailed("Broker not available", 1);   // 한계 1회 → 곧바로 DEAD
        return event;
    }

    private void repositoryHas(OutboxEvent... events) {
        when(repository.findByStatusOrderByIdAsc(eq(OutboxStatus.DEAD))).thenReturn(List.of(events));
    }

    @Test
    @DisplayName("DEAD 목록에 판단 근거가 실려 나온다 — 사유와 추적 컨텍스트")
    void deadEventsCarryWhatOperatorNeeds() {
        repositoryHas(deadEvent("EVT-1"));

        List<DeadEventResponse> dead = service.deadEvents();

        assertThat(dead).singleElement().satisfies(response -> {
            assertThat(response.eventId()).isEqualTo("EVT-1");
            assertThat(response.eventType()).isEqualTo(EventType.PAYMENT_COMPLETED);
            assertThat(response.lastError()).contains("Broker not available");
            assertThat(response.traceParent()).isEqualTo(TRACE_PARENT);
        });
    }

    /** 페이로드에는 결제 금액·회원 식별자가 들어 있다. 운영 화면과 로그로 퍼뜨리지 않는다. */
    @Test
    @DisplayName("응답에 페이로드는 담지 않는다")
    void deadEventsDoNotExposePayload() {
        repositoryHas(deadEvent("EVT-1"));

        assertThat(DeadEventResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("payload");
    }

    @Test
    @DisplayName("한 건을 발행 대기로 되돌린다")
    void requeuesSingleEvent() {
        OutboxEvent event = deadEvent("EVT-1");
        repositoryHas(event);

        assertThat(service.requeue("EVT-1")).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("DEAD 가 아닌 이벤트 번호에는 아무 일도 하지 않는다")
    void requeueReportsMiss() {
        repositoryHas(deadEvent("EVT-1"));

        assertThat(service.requeue("EVT-없음")).isFalse();
    }

    /** 브로커 장애처럼 원인이 하나였던 경우 — 한 건씩 누르게 하면 그것이 곧 사고다. */
    @Test
    @DisplayName("일괄 회수는 되돌린 건수를 돌려준다")
    void requeuesAll() {
        OutboxEvent first = deadEvent("EVT-1");
        OutboxEvent second = deadEvent("EVT-2");
        repositoryHas(first, second);

        assertThat(service.requeueAll()).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("되돌릴 것이 없으면 0 이다 — 정상 상태에서 부르는 것이 흔하다")
    void requeueAllOnEmpty() {
        repositoryHas();

        assertThat(service.requeueAll()).isZero();
    }
}
