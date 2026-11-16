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
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    JdbcTemplate jdbc;

    /** 조회가 DB의 {@code NOW(6)}로 판정하므로 재시도 시각도 같은 시계로 둔다. {@code interval}은 테스트 상수만 쓴다. */
    private void setNextAttemptFromDbNow(OutboxEvent event, String interval) {
        jdbc.update("UPDATE outbox_event SET next_attempt_at = NOW(6) + " + interval + " WHERE id = ?", event.getId());
    }

    /** 키마다 순서를 지키므로, 다른 테스트가 남긴 행이 끼어들지 않게 테스트마다 키를 새로 만든다. */
    private OutboxEvent pendingEvent() {
        return pendingEvent("ORD-" + UUID.randomUUID());
    }

    private OutboxEvent pendingEvent(String partitionKey) {
        return repository.save(OutboxEvent.pending(
                UUID.randomUUID().toString(), "Order", partitionKey,
                "OrderCreated", "stove.order.v1", partitionKey, "{}"));
    }

    private OutboxEvent dead(OutboxEvent event) {
        for (int attempt = 0; attempt < 10; attempt++) {
            event.markFailed("broker down", 10);
        }
        return repository.save(event);
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
    @DisplayName("[D-055·결정 155] 같은 키의 앞 이벤트가 DEAD면 뒤 이벤트를 집지 않고, 다른 키는 그대로 집는다")
    void deadEarlierEventHoldsLaterEventsOfTheSameKey() {
        String key = "ORD-" + UUID.randomUUID();
        OutboxEvent first = dead(pendingEvent(key));
        OutboxEvent second = pendingEvent(key);
        OutboxEvent otherKey = pendingEvent();

        assertThat(first.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DEAD);
        assertThat(isPicked(second))
                .as("앞이 DEAD인데 뒤가 나가면 소비자는 순서가 뒤집힌 채 받는다")
                .isFalse();
        assertThat(isPicked(otherKey)).isTrue();
    }

    @Test
    @DisplayName("[D-055·결정 155] DEAD인 앞 이벤트를 회수하면 같은 키의 앞뒤가 적재 순서대로 다시 대상이 된다")
    void requeuingTheDeadEarlierEventReleasesTheKeyInOrder() {
        String key = "ORD-" + UUID.randomUUID();
        OutboxEvent first = dead(pendingEvent(key));
        OutboxEvent second = pendingEvent(key);

        first.requeue();
        repository.save(first);

        List<Long> picked = repository.lockPendingBatch(500).stream()
                .filter(event -> event.getPartitionKey().equals(key))
                .map(OutboxEvent::getId)
                .toList();
        assertThat(picked).containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("[D-056] 같은 키의 앞 이벤트가 재시도 대기면, 그 뒤에 적재된 이벤트를 먼저 집지 않는다")
    void backedOffEarlierEventHoldsALaterEventOutsideItsBatch() {
        String key = "ORD-" + UUID.randomUUID();
        OutboxEvent first = pendingEvent(key);
        first.markFailed("timeout", 10);
        repository.save(first);
        // 백오프 1초에 기대면 느린 실행에서 앞도 발행 가능해져 거짓 실패한다. DB 시각 기준 한 시간 뒤로 고정한다.
        setNextAttemptFromDbNow(first, "INTERVAL 1 HOUR");
        // 앞 이벤트가 실패한 배치에 없던 뒤 이벤트다. holdRemainder는 같은 배치 안에서만 미룬다.
        OutboxEvent second = pendingEvent(key);

        assertThat(second.getNextAttemptAt()).isNull();
        assertThat(isPicked(second))
                .as("앞이 재시도를 기다리는 사이 뒤가 먼저 나가면 같은 키의 순서가 뒤집힌다")
                .isFalse();

        // 앞의 대기가 끝나면 앞뒤가 적재 순서대로 함께 대상이 된다.
        setNextAttemptFromDbNow(first, "INTERVAL -1 SECOND");
        List<Long> picked = repository.lockPendingBatch(500).stream()
                .filter(event -> event.getPartitionKey().equals(key))
                .map(OutboxEvent::getId)
                .toList();
        assertThat(picked).containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("같은 키의 앞뒤가 모두 발행 가능하면 한 배치에 함께 집는다(키 웨이브 유지)")
    void eligibleEventsOfTheSameKeyArePickedTogether() {
        String key = "ORD-" + UUID.randomUUID();
        OutboxEvent first = pendingEvent(key);
        OutboxEvent second = pendingEvent(key);

        List<Long> picked = repository.lockPendingBatch(500).stream()
                .filter(event -> event.getPartitionKey().equals(key))
                .map(OutboxEvent::getId)
                .toList();
        assertThat(picked).containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("같은 키의 앞 이벤트가 발행을 마쳤으면 뒤 이벤트를 집는다")
    void sentEarlierEventDoesNotHoldTheKey() {
        String key = "ORD-" + UUID.randomUUID();
        OutboxEvent first = pendingEvent(key);
        first.markSent();
        repository.save(first);
        OutboxEvent second = pendingEvent(key);

        assertThat(isPicked(second)).isTrue();
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
