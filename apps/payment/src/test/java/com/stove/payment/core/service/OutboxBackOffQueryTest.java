package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.test.InfraContainers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 릴레이 조회의 <b>시간 조건</b>을 실제 MySQL 로 검증한다.
 *
 * <p>{@code next_attempt_at} 필터는 네이티브 SQL 안에 있어 대역으로는 확인할 수 없다.
 * 이 조건이 빠지면 백오프를 아무리 계산해도 릴레이가 곧바로 다시 집어가므로
 * 재시도 예산이 순식간에 소진된다 — D-003 이 정확히 그 상태였다.
 *
 * <p>결제 모듈에서 검증하는 이유는 단순하다. Outbox 스키마가 7개 서비스에 동일하게 있고,
 * 여기가 이미 MySQL 컨테이너를 띄우는 모듈이다.
 *
 * <p>한때 {@code PaymentContextTest} 의 캐시된 컨텍스트에서 살아 있는 릴레이 스레드가
 * 여기서 만든 이벤트를 집어가는 경합이 있었다. 단언을 "이미 발행됐어도 통과"로 푸는 대신
 * 그쪽 폴링 주기를 1시간으로 재워 경합 자체를 없앴다 — 단언이 느슨해지면
 * 조회 조건이 깨져도 통과하므로 D-003 의 회귀 방어선이 사라진다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class OutboxBackOffQueryTest {

    @Autowired
    OutboxEventRepository outboxEventRepository;

    private OutboxEvent pendingEvent() {
        return outboxEventRepository.save(OutboxEvent.pending(
                UUID.randomUUID().toString(), "Payment", "ORD-" + UUID.randomUUID(),
                EventType.PAYMENT_COMPLETED, Topics.PAYMENT, "ORD-1", "{}"));
    }

    private boolean isPicked(OutboxEvent event) {
        return outboxEventRepository.lockPendingBatch(500).stream()
                .map(OutboxEvent::getEventId)
                .anyMatch(eventId -> eventId.equals(event.getEventId()));
    }

    @Test
    @DisplayName("[D-003] 갓 적재된 이벤트는 즉시 발행 대상이다")
    void freshEventIsPickedImmediately() {
        OutboxEvent event = pendingEvent();

        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(isPicked(event)).isTrue();
    }

    @Test
    @DisplayName("[D-003] 다음 시도 시각이 아직 안 된 이벤트는 집어가지 않는다")
    void backedOffEventIsSkippedUntilItsTime() {
        OutboxEvent event = pendingEvent();

        // 실패 처리하면 next_attempt_at 이 미래로 잡힌다
        event.markFailed("broker down", 10);
        outboxEventRepository.save(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(isPicked(event))
                .as("백오프 대기 중인 이벤트")
                .isFalse();
    }

    @Test
    @DisplayName("[D-003] DEAD 는 집어가지 않지만 회수하면 다시 대상이 된다")
    void deadIsSkippedUntilRequeued() {
        OutboxEvent event = pendingEvent();
        for (int attempt = 0; attempt < 10; attempt++) {
            event.markFailed("broker down", 10);
        }
        outboxEventRepository.save(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DEAD);
        assertThat(isPicked(event)).isFalse();

        event.requeue();
        outboxEventRepository.save(event);

        assertThat(isPicked(event)).isTrue();
    }

    @Test
    @DisplayName("[D-003] 운영이 DEAD 목록을 조회할 수 있다")
    void deadEventsAreQueryable() {
        OutboxEvent event = pendingEvent();
        for (int attempt = 0; attempt < 10; attempt++) {
            event.markFailed("broker down", 10);
        }
        outboxEventRepository.save(event);

        List<OutboxEvent> dead =
                outboxEventRepository.findByStatusOrderByIdAsc(OutboxEvent.OutboxStatus.DEAD);

        assertThat(dead).extracting(OutboxEvent::getEventId).contains(event.getEventId());
    }
}
