package com.stove.common.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox 레코드의 상태 전이. 인프라 없이 검증 가능한 순수 규칙만 다룬다.
 *
 * <p>핵심은 재시도 예산을 <b>시간</b>으로 잡는다는 것이다. 횟수만 세면
 * 장애 감내 시간이 폴링 주기에 묶여 짧은 장애에도 이벤트가 DEAD 로 굳는다.
 * 그리고 DEAD 는 종착점이 아니어야 한다 — 회수 경로가 없으면
 * 유실을 막으려고 만든 장치가 유실의 원인이 된다.
 */
class OutboxEventTest {

    /** {@code OutboxProperties} 의 max-retry 기본값 */
    private static final int DEFAULT_MAX_RETRY = 10;

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
    @DisplayName("[D-003] 실패할수록 다음 시도가 뒤로 밀린다")
    void retryDelayGrows() {
        assertThat(OutboxEvent.backOffDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxEvent.backOffDelay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxEvent.backOffDelay(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxEvent.backOffDelay(4)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("[D-003] 재시도 간격에는 상한이 있다 — 시프트가 넘쳐 음수가 되면 안 된다")
    void retryDelayIsCapped() {
        assertThat(OutboxEvent.backOffDelay(20)).isEqualTo(Duration.ofMinutes(5));
        assertThat(OutboxEvent.backOffDelay(1000)).isEqualTo(Duration.ofMinutes(5));
        assertThat(OutboxEvent.backOffDelay(0)).isPositive();
    }

    @Test
    @DisplayName("[D-003] 기본 설정의 장애 감내 시간이 현실적인 브로커 재시작을 넘긴다")
    void toleranceCoversRealisticOutage() {
        // 고정 간격이던 시절에는 max-retry(10) x poll-interval(1초) = 10초가 전부였다.
        // 브로커 롤링 재시작이나 리더 선출은 그보다 오래 걸리는 일이 흔하다.
        long toleranceSeconds = 0;
        for (int attempt = 1; attempt < DEFAULT_MAX_RETRY; attempt++) {
            toleranceSeconds += OutboxEvent.backOffDelay(attempt).toSeconds();
        }

        assertThat(toleranceSeconds).isGreaterThan(Duration.ofMinutes(5).toSeconds());
    }

    @Test
    @DisplayName("[D-003] 실패는 다음 시도 시각을 미래로 잡고, 성공은 그것을 지운다")
    void nextAttemptIsScheduledOnFailureAndClearedOnSuccess() {
        OutboxEvent event = pending();
        assertThat(event.getNextAttemptAt()).as("최초 적재는 즉시 대상").isNull();

        event.markFailed("broker down", 10);
        assertThat(event.getNextAttemptAt()).isNotNull().isAfter(Instant.now());

        event.markSent();
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("[D-003] DEAD 는 회수할 수 있다 — 유실 방지 장치가 유실을 만들면 안 된다")
    void deadCanBeRequeued() {
        OutboxEvent event = pending();
        for (int i = 0; i < 3; i++) {
            event.markFailed("broker down", 3);
        }
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getNextAttemptAt()).as("DEAD 는 대기 대상이 아니다").isNull();

        event.requeue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextAttemptAt()).as("회수 직후는 즉시 대상").isNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    @DisplayName("DEAD 가 아닌 이벤트에 회수를 걸어도 상태가 흔들리지 않는다")
    void requeueOnlyAffectsDead() {
        OutboxEvent event = pending();
        event.markFailed("broker down", 10);

        event.requeue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).as("진행 중인 재시도 예산은 보존된다").isEqualTo(1);
    }
}
