package com.stove.payment.core.domain;

import static com.stove.payment.RefundFixtures.execution;
import static com.stove.payment.RefundFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundCancellationWindowTest {
    private static final Instant RECEIVED = Instant.parse("2026-10-27T01:00:00.123456Z");
    private static final Instant FIRST = RECEIVED.plus(Duration.ofDays(1));

    @Test void theWindowStartsAtFirstDispatchPreparationRatherThanReceiptOrOrderTime() {
        RefundExecution execution = received();
        RefundExecutionRequest original = execution.request();
        RefundCancellationPermission permission = execution.prepareCancellation(FIRST);
        assertThat(execution.getReceivedAt()).isEqualTo(RECEIVED);
        assertThat(execution.getFirstCancellationPreparedAt()).isEqualTo(FIRST);
        assertThat(execution.getAutomaticCancellationStoppedAt()).isNull();
        assertThat(permission.idempotencyKey()).isEqualTo(original.attemptId());
        assertThat(permission.request()).isEqualTo(original);
        assertThat(permission.expiresAt()).isEqualTo(FIRST.plusSeconds(10800));
        assertThat(permission.maySendAt(FIRST)).isTrue();
        assertThat(execution.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
    }

    @Test void retriesKeepTheOriginalKeyBodyAndDeadlineThroughTheLastMicrosecond() {
        RefundExecution execution = received();
        RefundCancellationPermission first = execution.prepareCancellation(FIRST);
        Instant last = FIRST.plusSeconds(10800).minusNanos(1000);
        RefundCancellationPermission repeated = execution.prepareCancellation(last);
        assertThat(repeated).isEqualTo(first);
        assertThatCode(() -> repeated.requireCancellationAllowedAt(last)).doesNotThrowAnyException();
        assertThat(execution.getFirstCancellationPreparedAt()).isEqualTo(FIRST);
    }

    @Test void exactlyThreeHoursRequiresReviewAndPreservesThePendingRequestForLookup() {
        RefundExecution execution = received();
        RefundExecutionRequest original = execution.request();
        execution.prepareCancellation(FIRST);
        Instant deadline = FIRST.plusSeconds(10800);
        RefundCancellationPermission stopped = execution.prepareCancellation(deadline);
        assertThat(stopped.maySendAt(deadline)).isFalse();
        assertThat(stopped.requiresOperatorReviewAt(deadline)).isTrue();
        assertThat(stopped.request()).isEqualTo(original);
        assertThat(execution.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(execution.getAutomaticCancellationStoppedAt()).isEqualTo(deadline);
        assertThat(execution.prepareCancellation(deadline.plusSeconds(3600))).isEqualTo(stopped);
        assertThat(execution.getAutomaticCancellationStoppedAt()).isEqualTo(deadline);
    }

    @Test void aPreviouslyIssuedPermissionCannotAuthorizeADelayedPostAfterItsDeadline() {
        RefundCancellationPermission permission = received().prepareCancellation(FIRST);
        Instant deadline = FIRST.plusSeconds(10800);
        assertThat(permission.maySendAt(deadline)).isFalse();
        assertThat(permission.requiresOperatorReviewAt(deadline)).isTrue();
        assertThatThrownBy(() -> permission.requireCancellationAllowedAt(deadline)).isInstanceOf(BusinessException.class);
    }

    @Test void anObservedStopCannotBeUndoneByRestartOrAClockMovingBack() {
        RefundExecution execution = received();
        execution.prepareCancellation(FIRST);
        execution.prepareCancellation(FIRST.plusSeconds(10800));
        RefundCancellationPermission permission = execution.prepareCancellation(FIRST.plusSeconds(1));
        assertThat(permission.maySendAt(FIRST.plusSeconds(1))).isFalse();
        assertThat(permission.requiresOperatorReviewAt(FIRST.plusSeconds(1))).isTrue();
        assertThat(execution.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
    }

    @Test void invalidPreparationTimesCannotStartOrExtendTheWindow() {
        RefundExecution execution = received();
        assertThatThrownBy(() -> execution.prepareCancellation(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution.prepareCancellation(FIRST.plusNanos(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution.prepareCancellation(RECEIVED.minusNanos(1000))).isInstanceOf(IllegalArgumentException.class);
        assertThat(execution.getFirstCancellationPreparedAt()).isNull();
    }

    private RefundExecution received() {
        Payment payment = paid();
        return RefundExecution.receive(payment, execution(payment), RECEIVED);
    }
}
