package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox 레코드의 상태 전이. 인프라 없이 검증 가능한 순수 규칙만 다룬다.
 *
 * <p>여기서 중요한 것은 {@code DEAD} 의 의미다 — 이벤트 유실을 막으려고 도입한 장치가
 * 정작 자기 자신을 유실시키는 종착점을 갖고 있다.
 */
class OutboxEventTest {

    private static OutboxEvent pending() {
        return OutboxEvent.pending("EVT-1", "Payment", "ORD-1",
                EventType.PAYMENT_COMPLETED, Topics.PAYMENT, "ORD-1", "{}");
    }

    @Test
    @DisplayName("적재 직후에는 PENDING 이고 재시도 횟수는 0이다")
    void startsPending() {
        OutboxEvent event = pending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getSentAt()).isNull();
    }

    @Test
    @DisplayName("발행 성공은 SENT 로 전이하고 이전 오류 기록을 지운다")
    void markSentClearsError() {
        OutboxEvent event = pending();
        event.markFailed("일시 오류", 10);

        event.markSent();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    @DisplayName("한계 미만의 실패는 PENDING 을 유지한다 — 다음 폴링이 다시 집어간다")
    void failureBelowLimitStaysPending() {
        OutboxEvent event = pending();

        event.markFailed("broker down", 3);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("broker down");
    }

    @Test
    @DisplayName("한계에 도달하면 DEAD 로 전이한다")
    void failureAtLimitBecomesDead() {
        OutboxEvent event = pending();

        for (int i = 0; i < 3; i++) {
            event.markFailed("broker down", 3);
        }

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getRetryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("오류 메시지는 컬럼 길이(500)에 맞춰 잘린다")
    void truncatesLongError() {
        OutboxEvent event = pending();

        event.markFailed("x".repeat(1000), 10);

        assertThat(event.getLastError()).hasSize(500);
    }

    @Test
    @DisplayName("오류 메시지가 없어도 실패 처리는 동작한다 — NPE 로 릴레이가 멈추면 안 된다")
    void handlesNullError() {
        OutboxEvent event = pending();

        event.markFailed(null, 10);

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).isNull();
    }

    @Test
    @DisplayName("DEAD 는 종착점이다 — 상태를 되돌리는 전이가 존재하지 않는다")
    void deadIsTerminal() {
        OutboxEvent event = pending();
        for (int i = 0; i < 3; i++) {
            event.markFailed("broker down", 3);
        }
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);

        // DEAD 인데도 실패를 더 기록할 수는 있다. 반대로 PENDING 으로 되돌리는 수단은 없다.
        // 이 비대칭이 곧 '유실 방지 장치가 유실을 만드는' 지점이다.
        // 유일한 탈출구인 markSent 는 실제 발행에 성공해야만 불릴 수 있는데,
        // lockPendingBatch 가 PENDING 만 집으므로 DEAD 는 다시 발행 시도조차 되지 않는다.
        event.markFailed("여전히 실패", 3);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getRetryCount()).isEqualTo(4);
    }
}
