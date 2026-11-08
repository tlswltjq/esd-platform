package com.stove.payment.core.domain;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Decision 127: the operator closure of a delayed or unattributed refund execution. */
class RefundClosureTest {
    private static final Duration LEASE = Duration.ofSeconds(97);
    private final Instant received = Payment.now().plusSeconds(1);
    private final Instant prepared = received.plusSeconds(1);
    private final Instant delayed = prepared.plusSeconds(10800);
    private final Payment payment = paid();

    @Test void onlyAPendingExecutionPastTheWindowOrUnattributedIsClosable() {
        RefundExecution unprepared = RefundExecution.receive(payment, execution(payment), received);
        assertThat(unprepared.closableAt(delayed)).isFalse();
        RefundExecution execution = prepared();
        assertThat(execution.closableAt(delayed.minusNanos(1000))).isFalse();
        assertThat(execution.closableAt(delayed)).isTrue();
        assertThat(execution.unattributed()).isFalse();
        RefundExecution early = prepared();
        early.confirmRefund(payment, confirmation(), id(), prepared.plusSeconds(10));
        assertThat(early.unattributed()).isTrue();
        assertThat(early.closableAt(prepared.plusSeconds(11))).isTrue();
    }

    @Test void ownSuccessAndConfirmedFailureAreNotClosable() {
        RefundExecution succeeded = prepared();
        succeeded.freezeCancellationBody("{\"reason\":\"review\"}");
        succeeded.confirmCancellationSuccess(payment, new PgRefundCancellationSuccess(purchase(), succeeded.getAttemptId(),
                new RefundCancellation("own", payment.getAmount(), prepared.plusSeconds(3)), prepared, 0, 0, "review"), id(), prepared.plusSeconds(3));
        assertThat(succeeded.closableAt(delayed)).isFalse();
        assertThat(succeeded.unattributed()).isFalse();
        RefundExecution failed = prepared();
        failed.freezeCancellationBody("{\"reason\":\"review\"}");
        PgRefundCancellationReceipt receipt = new PgRefundCancellationReceipt(purchase(), failed.getAttemptId(), "failed", PgRefundCancellationReceipt.State.FAILED, prepared, 0, 0, "review");
        failed.recordCancellationReceipt(payment, receipt, prepared.plusSeconds(1));
        failed.confirmFailure(payment, new PgRefundFailureConfirmation(receipt, "a".repeat(64)), id(), prepared.plusSeconds(1));
        assertThat(failed.closableAt(delayed)).isFalse();
    }

    @Test void aClosureRecordsTheJudgmentOperatorEvidenceAndOutcomeOfAnUnattributedExecution() {
        RefundExecution execution = prepared();
        execution.confirmRefund(payment, confirmation(), id(), delayed);
        RefundClosureRequest request = new RefundClosureRequest(id(), execution.getAttemptId(), "usr_admin", RefundAttribution.EXTERNAL_CANCELLATION, "콘솔 취소 확인");
        RefundClosure closure = RefundClosure.record(execution, request, RefundClosure.Outcome.COMPLETED, delayed, delayed.plusSeconds(1));
        RefundClosureView view = closure.view();
        assertThat(view.attemptId()).isEqualTo(execution.getAttemptId());
        assertThat(view.attribution()).isEqualTo(RefundAttribution.EXTERNAL_CANCELLATION);
        assertThat(view.outcome()).isEqualTo(RefundClosure.Outcome.COMPLETED);
        assertThat(view.operatorSubject()).isEqualTo("usr_admin");
        assertThat(view.evidenceRefundedAt()).isEqualTo(execution.getProviderRefundedAt());
        assertThat(view.evidenceObservedAt()).isEqualTo(delayed);
        assertThat(view.closedAt()).isEqualTo(delayed.plusSeconds(1));
        closure.requireSame(request);
        for (RefundClosureRequest changed : List.of(
                new RefundClosureRequest(request.requestId(), request.attemptId(), "usr_admin", RefundAttribution.OUR_REQUEST, request.note()),
                new RefundClosureRequest(request.requestId(), request.attemptId(), "other_admin", request.attribution(), request.note()),
                new RefundClosureRequest(request.requestId(), id(), "usr_admin", request.attribution(), request.note()),
                new RefundClosureRequest(request.requestId(), request.attemptId(), "usr_admin", request.attribution(), "다른 근거")))
            assertThatThrownBy(() -> closure.requireSame(changed)).isInstanceOf(BusinessException.class)
                    .extracting(failure -> ((BusinessException) failure).errorCode()).isEqualTo(PaymentErrorCode.REFUND_CLOSURE_CHANGED);
    }

    @Test void aClosureRequiresConfirmedMoneyOnTheSameAttemptAndOrderedTimes() {
        RefundExecution open = prepared();
        RefundClosureRequest request = new RefundClosureRequest(id(), open.getAttemptId(), "usr_admin", RefundAttribution.UNDETERMINED, "판단 불가");
        assertThatThrownBy(() -> RefundClosure.record(open, request, RefundClosure.Outcome.RECORDED, delayed, delayed)).isInstanceOf(IllegalArgumentException.class);
        open.confirmRefund(payment, confirmation(), id(), delayed);
        assertThatThrownBy(() -> RefundClosure.record(open, request, RefundClosure.Outcome.RECORDED, delayed, delayed.minusNanos(1000))).isInstanceOf(IllegalArgumentException.class);
        RefundClosureRequest other = new RefundClosureRequest(id(), id(), "usr_admin", RefundAttribution.UNDETERMINED, "판단 불가");
        assertThatThrownBy(() -> RefundClosure.record(open, other, RefundClosure.Outcome.RECORDED, delayed, delayed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundClosure.record(open, request, null, delayed, delayed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void requestsNeedACommandAttemptOperatorJudgmentAndBoundedNote() {
        String attempt = id();
        for (Runnable invalid : List.<Runnable>of(
                () -> new RefundClosureRequest("bad", attempt, "usr_admin", RefundAttribution.OUR_REQUEST, "근거"),
                () -> new RefundClosureRequest(id(), "bad", "usr_admin", RefundAttribution.OUR_REQUEST, "근거"),
                () -> new RefundClosureRequest(id(), attempt, " ", RefundAttribution.OUR_REQUEST, "근거"),
                () -> new RefundClosureRequest(id(), attempt, "usr_admin", null, "근거"),
                () -> new RefundClosureRequest(id(), attempt, "usr_admin", RefundAttribution.OUR_REQUEST, "\t"),
                () -> new RefundClosureRequest(id(), attempt, "usr_admin", RefundAttribution.OUR_REQUEST, "x".repeat(501))))
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        assertThat(RefundAttribution.parse("EXTERNAL_CANCELLATION")).isEqualTo(RefundAttribution.EXTERNAL_CANCELLATION);
        assertThat(RefundAttribution.parse("external_cancellation")).isNull();
        assertThat(RefundAttribution.parse(null)).isNull();
    }

    @Test void theClosureTakesTheWorkerLeaseWithoutWaitingForTheNextLookup() {
        RefundExecution execution = prepared();
        RefundLookupSchedule schedule = execution.getLookupSchedule();
        Instant nextAt = schedule.getNextAt();
        String token = id();
        assertThat(schedule.claimForClosure(token, prepared.plusSeconds(1), LEASE)).isTrue();
        assertThat(schedule.ownsClosure(token, prepared.plusSeconds(1))).isTrue();
        assertThat(schedule.claim(id(), nextAt, LEASE)).as("the worker waits for the closure lease").isNull();
        assertThat(schedule.claimForClosure(id(), prepared.plusSeconds(2), LEASE)).isFalse();
        assertThat(schedule.ownsClosure(token, prepared.plusSeconds(1).plus(LEASE))).isFalse();
        schedule.releaseClosure(id());
        assertThat(schedule.getClaimId()).isEqualTo(token);
        schedule.releaseClosure(token);
        assertThat(schedule.getClaimId()).isNull(); assertThat(schedule.getLeaseUntil()).isNull();
        assertThat(schedule.getNextAt()).as("the worker keeps its own schedule").isEqualTo(nextAt);
        RefundLookupClaim worker = schedule.claim(id(), nextAt, LEASE);
        assertThat(worker).isNotNull();
        assertThat(schedule.claimForClosure(id(), nextAt, LEASE)).as("the closure waits for the worker lease").isFalse();
    }

    /** No worker runs after an unattributed confirmation, and the database keeps no lease without a lookup deadline. */
    @Test void anUnattributedExecutionHasNoWorkerLeaseToTake() {
        RefundExecution execution = prepared();
        execution.confirmRefund(payment, confirmation(), id(), prepared.plusSeconds(10));
        RefundLookupSchedule schedule = execution.getLookupSchedule();
        assertThat(schedule.getNextAt()).isNull();
        assertThatThrownBy(() -> schedule.claimForClosure(id(), prepared.plusSeconds(20), LEASE)).isInstanceOf(IllegalStateException.class);
        assertThat(schedule.getClaimId()).isNull(); assertThat(schedule.getLeaseUntil()).isNull();
    }

    private RefundExecution prepared() {
        RefundExecution execution = RefundExecution.receive(payment, execution(payment), received);
        execution.prepareCancellation(prepared);
        return execution;
    }

    private Payment paid() {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW",
                "pg-original", Instant.parse("2026-10-16T00:00:00.123456Z"), PgResult.Decision.APPROVED,
                null, null, null), id(), Payment.now());
        return paid;
    }

    private PgRefundLookupRequest purchase() { return PgRefundLookupRequest.from(payment); }

    private PgRefundConfirmation confirmation() {
        return new PgRefundConfirmation(purchase(), List.of(new RefundCancellation("console-1", payment.getAmount(), prepared.plusSeconds(5))));
    }
}
