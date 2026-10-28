package com.stove.payment.core.domain;

import static com.stove.payment.RefundFixtures.execution;
import static com.stove.payment.RefundFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundDelayTest {
    private static final Instant RECEIVED = Instant.parse("2026-10-27T01:00:00.123456Z");
    private static final Instant FIRST = RECEIVED.plusSeconds(86400);

    @Test void originalPreparationDefinesTheInclusiveDelayBoundaryWithoutObservingAStop() {
        Payment payment = paid();
        RefundExecution execution = RefundExecution.receive(payment, execution(payment), RECEIVED);
        execution.prepareCancellation(FIRST);
        assertThat(execution.confirmationDelayedSince(FIRST.plusSeconds(10800).minusNanos(1000))).isNull();
        assertThat(execution.confirmationDelayedSince(FIRST.plusSeconds(10800))).isEqualTo(FIRST.plusSeconds(10800));
        assertThat(execution.confirmationDelayedSince(FIRST.plusSeconds(20000))).isEqualTo(FIRST.plusSeconds(10800));
        assertThat(execution.getAutomaticCancellationStoppedAt()).isNull();
        assertThat(execution.getLookupSchedule().getNextAt()).isEqualTo(FIRST.plusSeconds(5));
    }

    @Test void anOldReceiptWithoutActualPreparationIsNotADelayTarget() {
        Payment payment = paid();
        RefundExecution execution = RefundExecution.receive(payment, execution(payment), RECEIVED);
        assertThat(execution.confirmationDelayedSince(FIRST.plusSeconds(10800))).isNull();
    }
}
