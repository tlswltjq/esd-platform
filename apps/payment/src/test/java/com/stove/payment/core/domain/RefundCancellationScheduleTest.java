package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundCancellationScheduleTest {
    private static final Instant BASE = Instant.parse("2026-10-27T01:00:00.123456Z");
    private static final Duration LEASE = Duration.ofSeconds(231);

    @Test void waitsTenThenThirtyThenSixtySecondsFromEachCompletedCycle() {
        RefundCancellationSchedule schedule = new RefundCancellationSchedule();
        RefundCancellationClaim first = schedule.claim(id(), BASE, LEASE, false);
        assertThat(first).isNotNull();
        Instant finished = BASE.plusSeconds(70);
        schedule.finish(first, finished);
        assertThat(schedule.getNextAt()).isEqualTo(finished.plusSeconds(10));
        assertThat(schedule.claim(id(), finished.plusSeconds(9), LEASE, true)).isNull();
        RefundCancellationClaim retry = schedule.claim(id(), finished.plusSeconds(10), LEASE, true);
        schedule.finish(retry, finished.plusSeconds(90));
        assertThat(schedule.getNextAt()).isEqualTo(finished.plusSeconds(120));
        RefundCancellationClaim again = schedule.claim(id(), finished.plusSeconds(120), LEASE, true);
        schedule.finish(again, finished.plusSeconds(180));
        assertThat(schedule.getNextAt()).isEqualTo(finished.plusSeconds(240));
        assertThat(schedule.getStage()).isEqualTo(2);
    }

    @Test void anActiveCycleCannotOverlapEvenWhenForcedOrTheScheduledTimePasses() {
        RefundCancellationSchedule schedule = new RefundCancellationSchedule();
        RefundCancellationClaim first = schedule.claim(id(), BASE, LEASE, false);
        assertThat(first).isNotNull();
        assertThat(schedule.claim(id(), BASE.plusSeconds(60), LEASE, false)).isNull();
        assertThat(schedule.ownsAt(first, BASE.plusSeconds(230))).isTrue();
        assertThat(schedule.ownsAt(first, BASE.plus(LEASE))).isFalse();
    }

    @Test void crashedWorkWaitsForItsLeaseAndBackoffAndAnOldFinishCannotOverwriteItsSuccessor() {
        RefundCancellationSchedule schedule = new RefundCancellationSchedule();
        RefundCancellationClaim old = schedule.claim(id(), BASE, LEASE, false);
        Instant recovery = BASE.plus(LEASE).plusSeconds(10);
        assertThat(schedule.getNextAt()).isEqualTo(recovery);
        assertThat(schedule.claim(id(), recovery.minusNanos(1000), LEASE, true)).isNull();
        RefundCancellationClaim replacement = schedule.claim(id(), recovery, LEASE, true);
        assertThat(replacement).isNotNull();
        schedule.finish(old, recovery.plusSeconds(1));
        assertThat(schedule.getClaimId()).isEqualTo(replacement.token());
        schedule.finish(replacement, recovery.plusSeconds(2));
        assertThat(schedule.getNextAt()).isEqualTo(recovery.plusSeconds(32));
    }

    @Test void legacyPreparationStartsAConservativeWaitWithoutInventingACompletionTime() {
        RefundCancellationSchedule schedule = new RefundCancellationSchedule();
        schedule.initializeUnknown(BASE);
        schedule.initializeUnknown(BASE.plusSeconds(100));
        assertThat(schedule.getNextAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(schedule.getLastFinishedAt()).isNull();
        RefundCancellationClaim claim = schedule.claim(id(), BASE.plusSeconds(10), LEASE, true);
        schedule.finish(claim, BASE.plusSeconds(12));
        assertThat(schedule.getNextAt()).isEqualTo(BASE.plusSeconds(42));
    }

    @Test void rejectedSubmissionDoesNotConsumeTheRetryStageOrLeaveAnActiveClaim() {
        RefundCancellationSchedule schedule = new RefundCancellationSchedule();
        schedule.initializeUnknown(BASE);
        RefundCancellationClaim rejected = schedule.claim(id(), BASE.plusSeconds(10), LEASE, true);
        schedule.abandon(rejected);
        assertThat(schedule.getClaimId()).isNull();
        assertThat(schedule.getStage()).isEqualTo(1);
        assertThat(schedule.getNextAt()).isEqualTo(BASE.plusSeconds(10));
        RefundCancellationClaim actual = schedule.claim(id(), BASE.plusSeconds(10), LEASE, true);
        schedule.finish(actual, BASE.plusSeconds(20));
        assertThat(schedule.getNextAt()).isEqualTo(BASE.plusSeconds(50));
    }

    @Test void stoppingAndAReversedClockCannotRestartOrAccelerateTheSchedule() {
        RefundCancellationSchedule schedule = new RefundCancellationSchedule();
        RefundCancellationClaim claim = schedule.claim(id(), BASE, LEASE, false);
        schedule.finish(claim, BASE.minusSeconds(1));
        assertThat(schedule.getNextAt()).isEqualTo(BASE.plusSeconds(10));
        RefundCancellationClaim next = schedule.claim(id(), BASE.plusSeconds(10), LEASE, true);
        schedule.stop(); schedule.finish(next, BASE.plusSeconds(15));
        assertThat(schedule.getNextAt()).isNull();
        assertThat(schedule.getClaimId()).isNull();
        assertThat(schedule.claim(id(), BASE.plusSeconds(100), LEASE, true)).isNull();
    }

    @Test void invalidClaimsAndTimesCannotBecomeDurableScheduleValues() {
        RefundCancellationSchedule schedule = new RefundCancellationSchedule();
        assertThatThrownBy(() -> new RefundCancellationClaim("bad", BASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.claim(id(), BASE.plusNanos(1), LEASE, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.claim(id(), BASE, Duration.ZERO, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.initializeUnknown(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
