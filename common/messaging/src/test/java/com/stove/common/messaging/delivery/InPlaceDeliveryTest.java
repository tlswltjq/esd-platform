package com.stove.common.messaging.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
