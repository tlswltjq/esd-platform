package com.stove.payment.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundLookupScheduleTest {
    private static final Instant FIRST = Instant.parse("2026-10-27T01:00:00.123456Z");
    private static final Duration LEASE = Duration.ofSeconds(97);

    @Test void periodicChecksFollowFiveFifteenThirtyFiveThenEveryMinute() {
        RefundLookupSchedule schedule = prepared();
        assertThat(schedule.getNextAt()).isEqualTo(FIRST.plusSeconds(5));
        for (long second : new long[] {5, 15, 35, 95, 155}) {
            RefundLookupClaim claim = schedule.claim(token(), FIRST.plusSeconds(second), LEASE);
            assertThat(claim).isNotNull();
            schedule.finish(claim, FIRST, FIRST.plusSeconds(second));
            long delay = second == 5 ? 10 : second == 15 ? 20 : 60;
            assertThat(schedule.getNextAt()).isEqualTo(FIRST.plusSeconds(second + delay));
        }
    }

    @Test void anotherPreparationOrAnEarlyScanDoesNotResetTheDeadline() {
        RefundLookupSchedule schedule = prepared();
        schedule.prepare(FIRST.plusSeconds(2));
        assertThat(schedule.claim(token(), FIRST.plusSeconds(4), LEASE)).isNull();
        assertThat(schedule.getNextAt()).isEqualTo(FIRST.plusSeconds(5));
    }

    @Test void restartAfterHoursSkipsMissedChecksAndKeepsReadOnlyChecksAlive() {
        RefundLookupSchedule schedule = prepared();
        Instant resumed = FIRST.plusSeconds(14400);
        RefundLookupClaim claim = schedule.claim(token(), resumed, LEASE);
        schedule.finish(claim, FIRST, resumed);
        // Four hours after preparation the original window has passed: the capped post-window delay (decision 122 formula).
        assertThat(schedule.getNextAt()).isEqualTo(resumed.plusSeconds(600));
        assertThat(schedule.getStage()).isEqualTo(3);
    }

    /** Past the 3-hour window: min(max, max(initial, at - firstPreparedAt)); inside it the 60-second step is unchanged. */
    @Test void pastTheWindowTheDelayIsTheElapsedTimeBetweenTheInitialDelayAndTheCap() {
        RefundLookupInterval interval = new RefundLookupInterval(Duration.ofSeconds(60), Duration.ofMinutes(10));
        RefundLookupSchedule inside = prepared();
        Instant last = FIRST.plusSeconds(10799);
        RefundLookupClaim claim = inside.claim(token(), last, LEASE);
        inside.finish(claim, FIRST, last, interval, false);
        assertThat(inside.getNextAt()).isEqualTo(last.plusSeconds(60));
        Instant edge = FIRST.plusSeconds(10800);
        RefundLookupSchedule past = prepared();
        past.finish(past.claim(token(), edge, LEASE), FIRST, edge, interval, false);
        assertThat(past.getNextAt()).isEqualTo(edge.plusSeconds(600));
        // A lowered cap (and initial) is honoured.
        RefundLookupSchedule lowered = prepared();
        lowered.finish(lowered.claim(token(), edge, LEASE), FIRST, edge, new RefundLookupInterval(Duration.ofSeconds(30), Duration.ofSeconds(90)), false);
        assertThat(lowered.getNextAt()).isEqualTo(edge.plusSeconds(90));
    }

    /** Decision 128: a failure confirmed inside the window is watched at the low frequency, not every minute. */
    @Test void aFailedExecutionUsesTheLowFrequencyDelayEvenInsideTheWindow() {
        RefundLookupInterval interval = new RefundLookupInterval(Duration.ofSeconds(60), Duration.ofMinutes(10));
        RefundLookupSchedule early = prepared();
        Instant soon = FIRST.plusSeconds(20);
        early.finish(early.claim(token(), soon, LEASE), FIRST, soon, interval, true);
        assertThat(early.getNextAt()).as("max(initial, 20s)").isEqualTo(soon.plusSeconds(60));
        RefundLookupSchedule later = prepared();
        Instant fiveMinutes = FIRST.plusSeconds(300);
        later.finish(later.claim(token(), fiveMinutes, LEASE), FIRST, fiveMinutes, interval, true);
        assertThat(later.getNextAt()).as("the elapsed 300s").isEqualTo(fiveMinutes.plusSeconds(300));
    }

    /** A webhook wake is not bound by the long post-window delay. */
    @Test void aWebhookWakeIsClaimableImmediatelyAfterALongDelay() {
        RefundLookupSchedule schedule = prepared();
        Instant edge = FIRST.plusSeconds(10800);
        schedule.finish(schedule.claim(token(), edge, LEASE), FIRST, edge, RefundLookupInterval.DEFAULT, false);
        assertThat(schedule.getNextAt()).isEqualTo(edge.plusSeconds(600));
        assertThat(schedule.claim(token(), edge.plusSeconds(1), LEASE)).isNull();
        schedule.wake(edge.plusSeconds(1));
        assertThat(schedule.claim(token(), edge.plusSeconds(1), LEASE)).isNotNull();
    }

    @Test void theIntervalCapCannotExceedTenMinutes() {
        assertThat(RefundLookupInterval.DEFAULT).isEqualTo(new RefundLookupInterval(Duration.ofSeconds(60), Duration.ofMinutes(10)));
        assertThatThrownBy(() -> new RefundLookupInterval(Duration.ofSeconds(60), Duration.ofMinutes(10).plusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundLookupInterval(Duration.ofSeconds(120), Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundLookupInterval(Duration.ZERO, Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void slowHttpDoesNotProduceACatchUpBurst() {
        RefundLookupSchedule schedule = prepared();
        RefundLookupClaim claim = schedule.claim(token(), FIRST.plusSeconds(5), LEASE);
        schedule.finish(claim, FIRST, FIRST.plusSeconds(70));
        assertThat(schedule.getNextAt()).isEqualTo(FIRST.plusSeconds(130));
    }

    @Test void earlyWebhookDoesNotResetOrAdvanceThePeriodicSchedule() {
        RefundLookupSchedule schedule = prepared();
        schedule.wake(FIRST.plusSeconds(1));
        RefundLookupClaim claim = schedule.claim(token(), FIRST.plusSeconds(1), LEASE);
        schedule.finish(claim, FIRST, FIRST.plusSeconds(2));
        assertThat(schedule.getNextAt()).isEqualTo(FIRST.plusSeconds(5));
        assertThat(schedule.getWakeAt()).isNull();
    }

    @Test void webhookDuringHttpIsPreservedWithoutStealingTheLease() {
        RefundLookupSchedule schedule = prepared();
        RefundLookupClaim first = schedule.claim(token(), FIRST.plusSeconds(5), LEASE);
        schedule.wake(FIRST.plusSeconds(6));
        assertThat(schedule.claim(token(), FIRST.plusSeconds(7), LEASE)).isNull();
        schedule.finish(first, FIRST, FIRST.plusSeconds(8));
        assertThat(schedule.getWakeAt()).isEqualTo(FIRST.plusSeconds(6));
        assertThat(schedule.claim(token(), FIRST.plusSeconds(8), LEASE)).isNotNull();
    }

    @Test void expiredWorkerCannotReleaseTheNewWorkersLeaseOrRescheduleIt() {
        RefundLookupSchedule schedule = prepared();
        RefundLookupClaim old = schedule.claim(token(), FIRST.plusSeconds(5), LEASE);
        RefundLookupClaim current = schedule.claim(token(), FIRST.plusSeconds(102), LEASE);
        assertThat(current).isNotNull();
        schedule.finish(old, FIRST, FIRST.plusSeconds(103));
        assertThat(schedule.getClaimId()).isEqualTo(current.token());
        schedule.finish(current, FIRST, FIRST.plusSeconds(104));
        assertThat(schedule.getNextAt()).isEqualTo(FIRST.plusSeconds(162));
    }

    @Test void executorRejectionDoesNotSpendABackoffStepOrLoseAWakeup() {
        RefundLookupSchedule schedule = prepared();
        schedule.wake(FIRST.plusSeconds(1));
        RefundLookupClaim claim = schedule.claim(token(), FIRST.plusSeconds(1), LEASE);
        schedule.abandon(claim);
        assertThat(schedule.getStage()).isZero();
        assertThat(schedule.claim(token(), FIRST.plusSeconds(2), LEASE)).isNotNull();
    }

    @Test void unpreparedOrCompletedExecutionCannotBeWokenAndLateFinishCannotReopenIt() {
        RefundLookupSchedule empty = new RefundLookupSchedule();
        empty.wake(FIRST);
        assertThat(empty.claim(token(), FIRST, LEASE)).isNull();
        RefundLookupSchedule schedule = prepared();
        RefundLookupClaim claim = schedule.claim(token(), FIRST.plusSeconds(5), LEASE);
        schedule.stop();
        schedule.finish(claim, FIRST, FIRST.plusSeconds(6));
        schedule.wake(FIRST.plusSeconds(7));
        assertThat(schedule.getNextAt()).isNull();
        assertThat(schedule.getClaimId()).isNull();
        assertThat(schedule.getWakeAt()).isNull();
    }

    private RefundLookupSchedule prepared() { RefundLookupSchedule schedule = new RefundLookupSchedule(); schedule.prepare(FIRST); return schedule; }
    private String token() { return UUID.randomUUID().toString(); }
}
