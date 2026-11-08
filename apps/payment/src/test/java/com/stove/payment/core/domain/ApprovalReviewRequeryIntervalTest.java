package com.stove.payment.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Decision 122: a held approval is looked up again after min(cap, max(initial, now - reviewStartedAt)). */
class ApprovalReviewRequeryIntervalTest {
    private static final Instant HELD = Instant.parse("2026-11-08T01:00:00.123456Z");
    private static final ApprovalReviewRequeryInterval INTERVAL =
            new ApprovalReviewRequeryInterval(Duration.ofSeconds(5), Duration.ofMinutes(10));

    @Test void aFreshHoldStartsAtTheInitialDelay() {
        assertThat(INTERVAL.after(HELD, HELD)).isEqualTo(Duration.ofSeconds(5));
        assertThat(INTERVAL.after(HELD, HELD.plusSeconds(4))).isEqualTo(Duration.ofSeconds(5));
        assertThat(INTERVAL.after(HELD, HELD.plusSeconds(5))).isEqualTo(Duration.ofSeconds(5));
    }

    @Test void theDelayGrowsWithTheTimeAlreadyHeld() {
        assertThat(INTERVAL.after(HELD, HELD.plusSeconds(6))).isEqualTo(Duration.ofSeconds(6));
        assertThat(INTERVAL.after(HELD, HELD.plusSeconds(90).plusNanos(1000))).isEqualTo(Duration.ofSeconds(90).plusNanos(1000));
    }

    @Test void theDelayStopsAtTheCap() {
        assertThat(INTERVAL.after(HELD, HELD.plusSeconds(599))).isEqualTo(Duration.ofSeconds(599));
        assertThat(INTERVAL.after(HELD, HELD.plusSeconds(600))).isEqualTo(Duration.ofMinutes(10));
        assertThat(INTERVAL.after(HELD, HELD.plusSeconds(86400))).isEqualTo(Duration.ofMinutes(10));
    }

    @Test void aLegacyHoldWithoutAStartUsesTheCap() {
        assertThat(INTERVAL.after(null, HELD)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test void aStartAfterNowNeverShortensBelowTheInitialDelay() {
        assertThat(INTERVAL.after(HELD.plusSeconds(30), HELD)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test void theIntervalRequiresAPositiveInitialDelayNotAboveTheCap() {
        assertThatThrownBy(() -> new ApprovalReviewRequeryInterval(Duration.ZERO, Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalReviewRequeryInterval(Duration.ofSeconds(5), Duration.ofSeconds(4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalReviewRequeryInterval(null, Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalReviewRequeryInterval(Duration.ofSeconds(5), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> INTERVAL.after(HELD, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
