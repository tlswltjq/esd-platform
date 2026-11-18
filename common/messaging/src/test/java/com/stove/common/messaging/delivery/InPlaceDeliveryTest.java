package com.stove.common.messaging.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.event.EventHandler;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import com.stove.common.messaging.trace.ReplayTracing;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class InPlaceDeliveryTest {

    private final KeyQuarantine quarantine = mock(KeyQuarantine.class);
    private final InPlaceDelivery delivery = new InPlaceDelivery(quarantine, ReplayTracing.NONE,
            List.of(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
    private final InboundMessage message = new InboundMessage("fixture.v1", "K-1", "{}", Map.of(), "test");

    @SuppressWarnings("unchecked")
    private static final Class<? extends RuntimeException>[] NONE = new Class[0];

    @Test
    void whenOwnershipIsLostDuringRetriesItStopsWithoutRetryingOrQuarantining() {
        when(quarantine.stopped(anyString(), anyString(), anyString())).thenReturn(false);
        AtomicInteger attempts = new AtomicInteger();
        EventHandler failing = received -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        };
        AtomicInteger asked = new AtomicInteger();

        // 첫 재시도 직전까지만 내 것이다(D-058, PR #215 재리뷰 R1).
        assertThatThrownBy(() -> delivery.deliver("g", failing, NONE, message, () -> asked.incrementAndGet() < 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("점유를 잃어");

        assertThat(attempts.get()).as("최초 1회 + 점유가 남은 재시도 1회").isEqualTo(2);
        verify(quarantine, never()).quarantine(anyString(), any(), any());
    }

    @Test
    void ownershipIsCheckedAgainRightBeforeQuarantining() {
        when(quarantine.stopped(anyString(), anyString(), anyString())).thenReturn(false);
        EventHandler failing = received -> {
            throw new IllegalStateException("boom");
        };
        AtomicInteger asked = new AtomicInteger();

        // 재시도 3회 동안은 내 것, 보관 직전에 잃는다.
        assertThatThrownBy(() -> delivery.deliver("g", failing, NONE, message, () -> asked.incrementAndGet() <= 3))
                .isInstanceOf(IllegalStateException.class);

        verify(quarantine, never()).quarantine(anyString(), any(), any());
    }

    @Test
    void whileHeldAfterAFailedQuarantineLosingOwnershipStopsTheNextAttempt() {
        when(quarantine.stopped(anyString(), anyString(), anyString())).thenReturn(false);
        doThrow(new IllegalStateException("격리 DB 안 됨")).when(quarantine).quarantine(anyString(), any(), any());
        AtomicInteger attempts = new AtomicInteger();
        EventHandler failing = received -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        };
        AtomicInteger asked = new AtomicInteger();
        // 묻는 순서: 재시도 직전 3번 → 보관 직전 1번(내 것, 보관은 실패) → 붙잡기 전 1번(내 것) → 기다린 뒤 다시 들어가기 직전(잃음).
        BooleanSupplier running = () -> asked.incrementAndGet() <= 5;

        assertThatThrownBy(() -> delivery.deliverHolding("g", failing, NONE, message, running, Duration.ofMillis(1)))
                .isInstanceOf(IllegalStateException.class);

        verify(quarantine).quarantine(anyString(), any(), any());
        assertThat(asked.get()).isEqualTo(6);
        assertThat(attempts.get()).as("첫 처리(최초 + 재시도 3) 뒤 다시 들어가지 않는다").isEqualTo(4);
    }

    @Test
    void aMessageOfAnAlreadyStoppedKeyIsNotStoredWhenOwnershipIsLost() {
        when(quarantine.stopped(anyString(), anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> delivery.deliver("g", received -> {
        }, NONE, message, () -> false)).isInstanceOf(IllegalStateException.class);

        verify(quarantine, never()).quarantine(anyString(), any(), any());
    }

    @Test
    void withoutAnOwnershipCheckItStillRetriesThenQuarantines() {
        when(quarantine.stopped(anyString(), anyString(), anyString())).thenReturn(false);
        AtomicInteger attempts = new AtomicInteger();
        EventHandler failing = received -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        };

        delivery.deliver("g", failing, NONE, message);

        assertThat(attempts.get()).isEqualTo(4);
        verify(quarantine).quarantine(any(), any(), any());
    }
}
