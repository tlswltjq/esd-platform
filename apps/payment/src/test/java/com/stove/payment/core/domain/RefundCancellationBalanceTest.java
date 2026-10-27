package com.stove.payment.core.domain;

import static com.stove.payment.RefundFixtures.execution;
import static com.stove.payment.RefundFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundCancellationBalanceTest {
    private static final Instant FIRST = Instant.parse("2026-10-27T01:00:00.123456Z");

    @Test void aChangedBalanceStopsPostsWithoutDecidingFailureOrMovingTheOriginalWindow() {
        RefundExecution execution = received();
        execution.prepareCancellation(FIRST);
        execution.blockAutomaticCancellation(FIRST.plusSeconds(1));
        RefundCancellationPermission stopped = execution.prepareCancellation(FIRST.plusSeconds(2));
        assertThat(stopped.maySendAt(FIRST.plusSeconds(2))).isFalse();
        assertThat(stopped.requiresOperatorReviewAt(FIRST.plusSeconds(2))).isTrue();
        assertThat(execution.getCancellationBalanceChangedAt()).isEqualTo(FIRST.plusSeconds(1));
        assertThat(execution.getAutomaticCancellationStoppedAt()).isNull();
        assertThat(execution.getFirstCancellationPreparedAt()).isEqualTo(FIRST);
        assertThat(execution.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        execution.blockAutomaticCancellation(FIRST.plusSeconds(10));
        assertThat(execution.getCancellationBalanceChangedAt()).isEqualTo(FIRST.plusSeconds(1));
    }

    @Test void retriesAndNewDeploymentsUseTheFirstWireBodyInsteadOfRegeneratingIt() {
        RefundExecution execution = received(); execution.prepareCancellation(FIRST);
        assertThat(execution.freezeCancellationBody("first body")).isEqualTo("first body");
        assertThat(execution.freezeCancellationBody("newly generated body")).isEqualTo("first body");
        assertThat(execution.getCancellationRequestBody()).isEqualTo("first body");
    }

    @Test void unpreparedOrInvalidSnapshotsCannotAuthorizeAnExternalPost() {
        RefundExecution execution = received();
        assertThatThrownBy(() -> execution.freezeCancellationBody("body")).isInstanceOf(IllegalArgumentException.class);
        execution.prepareCancellation(FIRST);
        assertThatThrownBy(() -> execution.freezeCancellationBody(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execution.blockAutomaticCancellation(FIRST.minusNanos(1000))).isInstanceOf(IllegalArgumentException.class);
        assertThat(execution.getCancellationBalanceChangedAt()).isNull();
        assertThat(execution.getCancellationRequestBody()).isNull();
    }

    private RefundExecution received() { Payment payment = paid(); return RefundExecution.receive(payment, execution(payment), FIRST); }
}
