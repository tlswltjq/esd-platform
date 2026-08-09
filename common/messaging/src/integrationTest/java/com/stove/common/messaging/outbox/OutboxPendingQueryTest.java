package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.InfraContainers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code lockPendingBatch} 의 의미를 <b>이 모듈에서</b> 실제 MySQL 로 검증한다.
 *
 * <p>이 쿼리는 네이티브 SQL 이라 대역으로는 확인할 수 없다. 그런데 검증은
 * {@code apps/payment} 의 테스트 하나에만 있었다 — <b>9개 서비스가 의존하는 쿼리의
 * 회귀 방어선이 앱 하나에 인질로 잡혀 있었다</b>는 뜻이다. 그 앱이 테스트를 옮기거나
 * 지우면 나머지 8개는 아무 신호 없이 보호를 잃는다.
 *
 * <p>검증하는 성질은 셋이다.
 * <ol>
 *   <li>{@code status = 'PENDING'} — 발행 끝난 것과 포기한 것은 집지 않는다</li>
 *   <li>{@code next_attempt_at} 시간 조건 — 이게 빠지면 백오프를 계산해도 무의미하다(D-003)</li>
 *   <li>{@code ORDER BY id} + {@code LIMIT} — 적재 순서대로, 배치 크기만큼</li>
 * </ol>
 *
 * <p>앱 쪽 {@code OutboxBackOffQueryTest} 는 그대로 둔다. 그쪽은 "이 서비스의 스키마에서도
 * 같은 쿼리가 동작하는가"를 보는 통합 검증이고, 여기는 쿼리 자체의 계약이다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class OutboxPendingQueryTest {

    @Autowired
    OutboxEventRepository repository;

    private OutboxEvent pendingEvent() {
        return repository.save(OutboxEvent.pending(
                UUID.randomUUID().toString(), "Order", "ORD-" + UUID.randomUUID(),
                "OrderCreated", "stove.order.v1", "ORD-1", "{}"));
    }

    private boolean isPicked(OutboxEvent event) {
        return repository.lockPendingBatch(500).stream()
                .map(OutboxEvent::getEventId)
                .anyMatch(eventId -> eventId.equals(event.getEventId()));
    }

    @Test
    @DisplayName("갓 적재된 이벤트는 즉시 발행 대상이다")
    void freshEventIsPicked() {
        OutboxEvent event = pendingEvent();

        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(isPicked(event)).isTrue();
    }

    @Test
    @DisplayName("[D-003] 백오프 대기 중인 이벤트는 시간이 될 때까지 건너뛴다")
    void backedOffEventIsSkipped() {
        OutboxEvent event = pendingEvent();
        event.markFailed("broker down", 10);
        repository.save(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(isPicked(event))
                .as("시간 조건이 빠지면 재시도 예산이 폴링 주기만큼의 시간에 소진된다")
                .isFalse();
    }

    @Test
    @DisplayName("발행이 끝난 이벤트는 다시 집지 않는다")
    void sentEventIsNotPicked() {
        OutboxEvent event = pendingEvent();
        event.markSent();
        repository.save(event);

        assertThat(isPicked(event)).isFalse();
    }

    @Test
    @DisplayName("DEAD 는 집지 않지만 회수하면 다시 대상이 된다")
    void deadIsSkippedUntilRequeued() {
        OutboxEvent event = pendingEvent();
        for (int attempt = 0; attempt < 10; attempt++) {
            event.markFailed("broker down", 10);
        }
        repository.save(event);

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DEAD);
        assertThat(isPicked(event)).isFalse();

        event.requeue();
        repository.save(event);

        assertThat(isPicked(event)).isTrue();
    }

    @Test
    @DisplayName("배치는 적재 순서대로 크기만큼만 집는다")
    void batchIsOrderedAndBounded() {
        List<OutboxEvent> created = List.of(pendingEvent(), pendingEvent(), pendingEvent());

        List<OutboxEvent> batch = repository.lockPendingBatch(2);

        // LIMIT 이 없거나 ORDER BY 가 빠지면 순서 보장(키 웨이브)의 전제가 무너진다.
        assertThat(batch).hasSize(2);
        assertThat(batch).extracting(OutboxEvent::getId).isSorted();
        assertThat(created).extracting(OutboxEvent::getId).isSorted();
    }

    @Test
    @DisplayName("운영이 DEAD 목록을 조회할 수 있다")
    void deadEventsAreQueryable() {
        OutboxEvent event = pendingEvent();
        for (int attempt = 0; attempt < 10; attempt++) {
            event.markFailed("broker down", 10);
        }
        repository.save(event);

        assertThat(repository.findByStatusOrderByIdAsc(OutboxEvent.OutboxStatus.DEAD))
                .extracting(OutboxEvent::getEventId)
                .contains(event.getEventId());
    }
}
