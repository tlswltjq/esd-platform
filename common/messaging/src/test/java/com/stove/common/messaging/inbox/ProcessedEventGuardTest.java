package com.stove.common.messaging.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.event.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 컨슈머 멱등 가드. 중복 수신을 흡수하는 마지막 논리 방어선이다.
 *
 * <p>가드가 "처리해도 된다"고 답하는 순간 부수효과(라이선스 지급, 정산 원장 적재)가 일어나므로,
 * 판단 근거인 {@code eventId} 가 쓸 수 없는 값일 때 무엇을 하는지가 중요하다.
 */
class ProcessedEventGuardTest {

    private static final String GROUP = "license";

    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);
    private final ProcessedEventGuard guard = new ProcessedEventGuard(repository);

    @Test
    @DisplayName("최초 수신이면 처리를 허용하고 수신 기록을 남긴다")
    void firstDeliveryIsAllowed() {
        when(repository.existsByEventIdAndConsumerGroup("EVT-1", GROUP)).thenReturn(false);

        boolean proceed = guard.firstDelivery("EVT-1", GROUP, EventType.PAYMENT_COMPLETED);

        assertThat(proceed).isTrue();

        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo("EVT-1");
        assertThat(captor.getValue().getConsumerGroup()).isEqualTo(GROUP);
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.PAYMENT_COMPLETED);
    }

    @Test
    @DisplayName("이미 처리한 이벤트는 건너뛰고 기록을 덧쓰지 않는다")
    void duplicateDeliveryIsSkipped() {
        when(repository.existsByEventIdAndConsumerGroup("EVT-1", GROUP)).thenReturn(true);

        boolean proceed = guard.firstDelivery("EVT-1", GROUP, EventType.PAYMENT_COMPLETED);

        assertThat(proceed).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("컨슈머 그룹이 다르면 같은 이벤트도 각자 한 번씩 처리한다")
    void differentConsumerGroupsProcessIndependently() {
        // 같은 PaymentCompleted 를 license·order·settlement 가 각각 받아야 한다.
        // 그룹을 키에서 빼면 먼저 처리한 서비스가 나머지를 굶긴다.
        when(repository.existsByEventIdAndConsumerGroup("EVT-1", "license")).thenReturn(true);
        when(repository.existsByEventIdAndConsumerGroup("EVT-1", "settlement")).thenReturn(false);

        assertThat(guard.firstDelivery("EVT-1", "license", EventType.PAYMENT_COMPLETED)).isFalse();
        assertThat(guard.firstDelivery("EVT-1", "settlement", EventType.PAYMENT_COMPLETED)).isTrue();
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-004] eventId 가 없으면 가드가 처리를 거부해야 한다")
    void shouldRejectNullEventId() {
        // eventId 가 null 이면 '이미 처리했는지' 판단 자체가 불가능하다.
        // 그런데 현재 가드는 조회 결과가 false 라는 이유로 처리를 허용하고,
        // 실패는 event_id NOT NULL 제약에 걸리는 커밋 시점까지 미뤄진다.
        //
        // 그 결과 (1) 비즈니스 로직이 이미 실행된 뒤 롤백되고,
        //        (2) 오프셋이 커밋되지 않아 같은 레코드가 무한 재전송되며,
        //        (3) 파티션 전체가 그 자리에서 멈춘다.
        // 판단 불가능한 입력은 부수효과 이전에 끊어야 한다.
        assertThatThrownBy(() -> guard.firstDelivery(null, GROUP, EventType.PAYMENT_COMPLETED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("현재 동작: eventId 가 null 이어도 가드는 처리를 허용한다")
    void nullEventIdCurrentlyPassesGuard() {
        when(repository.existsByEventIdAndConsumerGroup(null, GROUP)).thenReturn(false);

        assertThat(guard.firstDelivery(null, GROUP, EventType.PAYMENT_COMPLETED)).isTrue();
        verify(repository).save(any(ProcessedEvent.class));
    }
}
