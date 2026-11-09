package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundCancellationHoldTest {
    private static final Instant BASE = Instant.parse("2026-10-28T01:00:00.123456Z");

    @Test void currentOwnerRejectionBlocksCommandsButKeepsTheRefundAndResultLookupPending() {
        Fixture fixture = fixture(); RefundExecution execution = fixture.execution();
        execution.holdCancellation(fixture.hold().getHoldId(), fixture.claim());
        assertThat(execution.getActiveCancellationHoldId()).isEqualTo(fixture.hold().getHoldId());
        assertThat(execution.prepareCancellation(BASE.plusSeconds(2)).automaticCancellationStopped()).isTrue();
        assertThat(execution.getCancellationSchedule().getNextAt()).isNull();
        assertThat(execution.getLookupSchedule().getNextAt()).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(execution.getFailureEventId()).isNull();
    }

    @Test void anOldOrDifferentOwnerCannotActivateARejectionForTheCurrentWork() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> fixture.execution().holdCancellation(fixture.hold().getHoldId(), new RefundCancellationClaim(id(), BASE)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void explicitResumePreservesOriginalRequestAndStageAndCannotBeOverwrittenByTheOldFinish() {
        Fixture fixture = fixture(); RefundExecution execution = fixture.execution(); RefundCancellationHold hold = fixture.hold();
        execution.holdCancellation(hold.getHoldId(), fixture.claim());
        RefundExecutionRequest original = execution.request();
        RefundCancellationResumeRequest resume = command(hold);
        assertThat(hold.recordResume(resume, BASE.plusSeconds(10))).isTrue();
        execution.resumeCancellation(hold.getHoldId(), BASE.plusSeconds(10));
        assertThat(execution.getActiveCancellationHoldId()).isNull();
        assertThat(execution.getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(execution.getCancellationSchedule().getStage()).isZero();
        assertThat(execution.getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(execution.getCancellationRequestBody()).isEqualTo("frozen-body");
        assertThat(execution.request()).isEqualTo(original);
        assertThat(hold.receipt()).isEqualTo(new RefundCancellationResumeReceipt(resume.requestId(), execution.getAttemptId(), hold.getHoldId(), BASE.plusSeconds(10)));
        execution.getCancellationSchedule().finish(fixture.claim(), BASE.plusSeconds(11));
        assertThat(execution.getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
    }

    @Test void wrongHoldOrExpiredOriginalWindowCannotBeResumed() {
        Fixture fixture = fixture(); fixture.execution().holdCancellation(fixture.hold().getHoldId(), fixture.claim());
        assertThatThrownBy(() -> fixture.execution().resumeCancellation(id(), BASE.plusSeconds(10))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.execution().resumeCancellation(fixture.hold().getHoldId(), BASE.plusSeconds(10800)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void readEligibilityUsesTheSameOriginalWindowWithoutMutatingTheHold() {
        Fixture fixture = fixture(); RefundExecution execution = fixture.execution();
        execution.holdCancellation(fixture.hold().getHoldId(), fixture.claim());
        assertThat(execution.cancellationResumeDeadline()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(execution.isCurrentCancellationHold(fixture.hold().getHoldId())).isTrue();
        assertThat(execution.canResumeCancellation(fixture.hold().getHoldId(), BASE.plusSeconds(10))).isTrue();
        assertThat(execution.canResumeCancellation(fixture.hold().getHoldId(), BASE.plusSeconds(10800))).isFalse();
        assertThat(execution.canResumeCancellation(id(), BASE.plusSeconds(10))).isFalse();
        assertThat(execution.getActiveCancellationHoldId()).isEqualTo(fixture.hold().getHoldId());
        execution.resumeCancellation(fixture.hold().getHoldId(), BASE.plusSeconds(10));
        assertThat(execution.isCurrentCancellationHold(fixture.hold().getHoldId())).isFalse();
        assertThat(execution.canResumeCancellation(fixture.hold().getHoldId(), BASE.plusSeconds(20))).isFalse();
    }

    @Test void oneOperatorCommandHasAnImmutableReceiptAndChangedOrNewCommandsCannotRewriteIt() {
        Fixture fixture = fixture(); RefundCancellationHold hold = fixture.hold(); RefundCancellationResumeRequest command = command(hold);
        assertThat(hold.recordResume(command, BASE.plusSeconds(10))).isTrue();
        RefundCancellationResumeReceipt first = hold.receipt();
        assertThat(hold.recordResume(command, BASE.plusSeconds(20))).isFalse();
        assertThat(hold.receipt()).isEqualTo(first);
        assertThatThrownBy(() -> hold.recordResume(new RefundCancellationResumeRequest(command.requestId(), command.attemptId(), command.holdId(),
                command.operatorSubject(), "changed review"), BASE.plusSeconds(20))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> hold.recordResume(command(hold), BASE.plusSeconds(20))).isInstanceOf(BusinessException.class);
    }

    @Test void anUnappliedStaleObservationCannotBeReleasedAsAnActiveHold() {
        Fixture fixture = fixture();
        RefundCancellationHold stale = RefundCancellationHold.forbidden(fixture.execution().getAttemptId(), fixture.claim(), BASE.plusSeconds(1), false);
        assertThatThrownBy(() -> stale.recordResume(command(stale), BASE.plusSeconds(10))).isInstanceOf(BusinessException.class);
    }

    @Test void newReceiptOrBalanceEvidenceBlocksResumeEvenInsideTheOriginalWindow() {
        Fixture balance = fixture(); balance.execution().holdCancellation(balance.hold().getHoldId(), balance.claim());
        balance.execution().blockAutomaticCancellation(BASE.plusSeconds(2));
        assertThatThrownBy(() -> balance.execution().resumeCancellation(balance.hold().getHoldId(), BASE.plusSeconds(10)))
                .isInstanceOf(BusinessException.class);
        Fixture received = fixture(); received.execution().holdCancellation(received.hold().getHoldId(), received.claim());
        Payment payment = received.payment();
        received.execution().recordCancellationReceipt(payment, new PgRefundCancellationReceipt(new PgRefundLookupRequest(payment.getPaymentId(),
                payment.getOrderNo(), payment.getAmount(), payment.getCurrency(), payment.getProviderTransactionId(), payment.getProviderOccurredAt()),
                received.execution().getAttemptId(), "cancel-requested", PgRefundCancellationReceipt.State.REQUESTED, BASE.plusSeconds(1),
                0, 0, "reviewed refund"), BASE.plusSeconds(2));
        assertThatThrownBy(() -> received.execution().resumeCancellation(received.hold().getHoldId(), BASE.plusSeconds(10)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void inputAndObservationValuesAreValidatedBeforeUse() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> new RefundCancellationResumeRequest(id(), id(), id(), " ", "review"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundCancellationResumeRequest("bad", id(), id(), "operator", "review"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundCancellationHold.forbidden(fixture.execution().getAttemptId(), fixture.claim(), BASE.minusSeconds(1), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anInvalidRequestHoldKeepsItsOwnReasonAndCanUseTheExistingResumeReceipt() {
        Fixture fixture = fixture();
        RefundCancellationHold invalid = RefundCancellationHold.invalidRequest(fixture.execution().getAttemptId(), fixture.claim(), BASE.plusSeconds(1), true);
        fixture.execution().holdCancellation(invalid.getHoldId(), fixture.claim());
        RefundCancellationResumeRequest request = command(invalid);
        assertThat(invalid.recordResume(request, BASE.plusSeconds(10))).isTrue();
        fixture.execution().resumeCancellation(invalid.getHoldId(), BASE.plusSeconds(10));
        assertThat(invalid.getReason()).isEqualTo("PG_REQUEST_INVALID");
        assertThat(invalid.getReason()).isNotEqualTo(fixture.hold().getReason());
        assertThat(invalid.receipt().requestId()).isEqualTo(request.requestId());
        assertThat(fixture.execution().getActiveCancellationHoldId()).isNull();
        assertThat(fixture.execution().getCancellationRequestBody()).isEqualTo("frozen-body");
        assertThat(fixture.execution().getFirstCancellationPreparedAt()).isEqualTo(BASE);
    }

    /** Decision 133: a formal rejection keeps its status and type and uses the same resume path as the other holds. */
    @Test void aFormalRejectionHoldKeepsItsStatusAndTypeAndCanUseTheExistingResumeReceipt() {
        Fixture fixture = fixture();
        RefundCancellationHold rejected = RefundCancellationHold.rejected(fixture.execution().getAttemptId(), fixture.claim(), BASE.plusSeconds(1), true,
                new PgCancellationRejection(409, "PAYMENT_NOT_PAID"));
        assertThat(rejected.getReason()).isEqualTo("PG_REQUEST_REJECTED");
        assertThat(rejected.getRejectionStatus()).isEqualTo(409); assertThat(rejected.getRejectionType()).isEqualTo("PAYMENT_NOT_PAID");
        assertThat(fixture.hold().getRejectionStatus()).isNull(); assertThat(fixture.hold().getRejectionType()).isNull();
        fixture.execution().holdCancellation(rejected.getHoldId(), fixture.claim());
        RefundCancellationResumeRequest request = command(rejected);
        assertThat(rejected.recordResume(request, BASE.plusSeconds(10))).isTrue();
        fixture.execution().resumeCancellation(rejected.getHoldId(), BASE.plusSeconds(10));
        assertThat(fixture.execution().getActiveCancellationHoldId()).isNull();
        assertThat(fixture.execution().getCancellationRequestBody()).isEqualTo("frozen-body");
        assertThatThrownBy(() -> RefundCancellationHold.rejected(fixture.execution().getAttemptId(), fixture.claim(), BASE.plusSeconds(1), true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The rejection value keeps the decision 133 boundaries: other statuses and the three existing 4xx meanings are refused. */
    @Test void aFormalRejectionExcludesStatusesAndTypesThatKeepTheirOwnMeaning() {
        assertThat(PgCancellationRejection.rejects(400, "OTHER_ERROR")).isTrue();
        assertThat(PgCancellationRejection.rejects(409, "PAYMENT_NOT_PAID")).isTrue();
        assertThat(PgCancellationRejection.rejects(422, "A_1")).isTrue();
        for (int status : new int[] {399, 401, 403, 404, 408, 429, 500, 502})
            assertThat(PgCancellationRejection.rejects(status, "OTHER_ERROR")).isFalse();
        assertThat(PgCancellationRejection.rejects(400, "INVALID_REQUEST")).isFalse();
        assertThat(PgCancellationRejection.rejects(409, "CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN")).isFalse();
        assertThat(PgCancellationRejection.rejects(409, "IDEMPOTENCY_OUTSTANDING_REQUEST")).isFalse();
        for (String type : new String[] {null, "", " ", "\t"})
            assertThat(PgCancellationRejection.rejects(409, type)).isFalse();
        for (String type : new String[] {"lower", "1ABC", "A B", "INVALID_REQUEST ", "결제 오류", "A".repeat(65), "X".repeat(5000)})
            assertThat(PgCancellationRejection.rejects(409, type)).as(type).isTrue();
        assertThat(PgCancellationRejection.rejects(400, "INVALID_REQUEST ")).isTrue();
        assertThatThrownBy(() -> new PgCancellationRejection(429, "TOO_MANY_REQUESTS")).isInstanceOf(IllegalArgumentException.class);
    }

    private RefundCancellationResumeRequest command(RefundCancellationHold hold) {
        return new RefundCancellationResumeRequest(id(), hold.getAttemptId(), hold.getHoldId(), "operator", "provider rejection reviewed");
    }

    private Fixture fixture() {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW", "pg-original", BASE.minusSeconds(60),
                PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(10));
        RefundExecution received = RefundExecution.receive(paid, execution(paid), BASE);
        received.prepareCancellation(BASE); received.freezeCancellationBody("frozen-body");
        RefundCancellationClaim claim = received.getCancellationSchedule().claim(id(), BASE, Duration.ofSeconds(231), false);
        RefundCancellationHold hold = RefundCancellationHold.forbidden(received.getAttemptId(), claim, BASE.plusSeconds(1), true);
        return new Fixture(paid, received, claim, hold);
    }

    private record Fixture(Payment payment, RefundExecution execution, RefundCancellationClaim claim, RefundCancellationHold hold) { }
}
