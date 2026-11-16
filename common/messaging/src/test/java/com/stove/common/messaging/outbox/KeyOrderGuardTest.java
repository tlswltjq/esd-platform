package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class KeyOrderGuardTest {

    private static OutboxEvent event(long id, String key) {
        OutboxEvent event = OutboxEvent.pending("e-" + id, "Order", key, "OrderPaid", "stove.order.v1", key, "{}");
        // id는 DB가 매긴다(IDENTITY). 순수 함수 시험이라 적재 순서를 직접 넣는다.
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private static OutboxKeyPosition position(long id, String key) {
        return new OutboxKeyPosition(key, id);
    }

    private static List<Long> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getId).toList();
    }

    @Test
    @DisplayName("[PR #203 R1] 같은 키의 앞 미발행 행이 배치에 없으면(다른 트랜잭션이 잠가 SKIP LOCKED로 빠짐) 그 키의 뒤 행을 보내지 않는다")
    void laterEventWaitsWhenAnEarlierUnsentEventIsNotInTheBatch() {
        List<OutboxEvent> batch = List.of(event(105, "ORD-7"), event(106, "ORD-8"));
        List<OutboxKeyPosition> unsent = List.of(
                position(101, "ORD-7"), position(105, "ORD-7"), position(106, "ORD-8"));

        assertThat(ids(KeyOrderGuard.releasable(batch, unsent))).containsExactly(106L);
    }

    @Test
    @DisplayName("같은 키의 앞 미발행 행이 모두 배치에 있으면 앞뒤를 적재 순서대로 보낸다")
    void eventsAreReleasedInOrderWhenEveryEarlierUnsentEventIsInTheBatch() {
        List<OutboxEvent> batch = List.of(event(101, "ORD-7"), event(105, "ORD-7"));
        List<OutboxKeyPosition> unsent = List.of(position(101, "ORD-7"), position(105, "ORD-7"));

        assertThat(ids(KeyOrderGuard.releasable(batch, unsent))).containsExactly(101L, 105L);
    }

    @Test
    @DisplayName("빠진 앞 행 뒤의 같은 키 행은 모두 기다리고, 그 앞의 행은 보낸다")
    void onlyEventsBeforeTheMissingOneAreReleased() {
        List<OutboxEvent> batch = List.of(event(101, "ORD-7"), event(107, "ORD-7"), event(109, "ORD-7"));
        List<OutboxKeyPosition> unsent = List.of(
                position(101, "ORD-7"), position(103, "ORD-7"), position(107, "ORD-7"), position(109, "ORD-7"));

        assertThat(ids(KeyOrderGuard.releasable(batch, unsent))).containsExactly(101L);
    }
}
