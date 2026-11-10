package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationWindow;
import com.stove.payment.core.domain.RefundClosure;
import com.stove.payment.core.domain.RefundClosureRepository;
import com.stove.payment.core.domain.RefundDelayKind;
import com.stove.payment.core.domain.RefundDelayLookup;
import com.stove.payment.core.domain.RefundDelayQuery;
import com.stove.payment.core.domain.RefundDelayView;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundFailureClosure;
import com.stove.payment.core.domain.RefundFailureClosureRepository;
import com.stove.payment.core.domain.RefundInvestigationRepository;
import com.stove.payment.core.domain.RefundInvestigation;
import com.stove.payment.core.domain.RefundInvestigationView;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadRefundDelays {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundCancellationHoldRepository holds;
    private final RefundInvestigationRepository investigations;
    private final RefundClosureRepository closures;
    private final RefundFailureClosureRepository failureClosures;
    private final Clock clock;

    public long count() {
        return executions.countDelayedForAdministration(RefundCancellationWindow.preparedBy(clock.instant()));
    }

    /** Open unattributed executions (decision 127); the gauge behind the {@code RefundsUnattributed} alert. */
    public long unattributedOpen() {
        return executions.countUnattributedOpen();
    }

    /** Failed executions with a full cancellation found later (decision 128); the gauge behind its alert. */
    public long lateCancellations() {
        return executions.countLateCancellationsAfterFailure();
    }

    public Page<RefundDelayView> search(RefundDelayQuery query) {
        return search(RefundDelayKind.DELAYED, query);
    }

    public Page<RefundDelayView> search(RefundDelayKind kind, RefundDelayQuery query) {
        if (kind == null || query == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Instant at = clock.instant();
        if (kind == RefundDelayKind.UNATTRIBUTED)
            return executions.findUnattributedOpen(PageRequest.of(query.page(), query.size(), Sort.by("refundConfirmedAt", "id")))
                    .map(execution -> view(execution, at));
        if (kind == RefundDelayKind.LATE_CANCELLATION)
            return executions.findLateCancellationsAfterFailure(PageRequest.of(query.page(), query.size(), Sort.by("lateCancellationObservedAt", "id")))
                    .map(execution -> view(execution, at));
        return executions.findDelayedForAdministration(RefundCancellationWindow.preparedBy(at),
                PageRequest.of(query.page(), query.size(), Sort.by("firstCancellationPreparedAt", "id"))).map(execution -> view(execution, at));
    }

    public RefundDelayView read(RefundDelayLookup query) {
        if (query == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Instant at = clock.instant();
        return view(find(query, at), at);
    }

    public Page<RefundInvestigationView> history(RefundDelayLookup lookup, RefundDelayQuery page) {
        if (lookup == null || page == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        find(lookup, clock.instant());
        return investigations.findByAttemptId(lookup.attemptId(),
                PageRequest.of(page.page(), page.size(), Sort.by("recordedAt", "id"))).map(RefundInvestigation::view);
    }

    private RefundExecution find(RefundDelayLookup query, Instant at) {
        RefundExecution execution = executions.findByAttemptId(query.attemptId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Payment payment = payments.findByPaymentId(execution.getPaymentId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // An unattributed execution can be confirmed inside the window; it is an investigation target from then on (decision 127).
        // A failure-closed or late-cancelled execution can be inside the window too (decision 128).
        boolean investigated = "PG_QUERY_UNATTRIBUTED".equals(execution.getRefundConfirmationSource())
                || execution.getLateCancellationObservedAt() != null || failureClosures.findByAttemptId(execution.getAttemptId()).isPresent();
        if (!"PORTONE_TEST".equals(payment.getProvider()) || execution.cancellationResumeDeadline() == null
                || (!investigated && at.isBefore(execution.cancellationResumeDeadline()))) throw new BusinessException(ErrorCode.NOT_FOUND);
        return execution;
    }

    private RefundDelayView view(RefundExecution execution, Instant at) {
        RefundCancellationHold hold = execution.getActiveCancellationHoldId() == null ? null
                : holds.findByHoldId(execution.getActiveCancellationHoldId()).orElse(null);
        RefundClosure closure = closures.findByAttemptId(execution.getAttemptId()).orElse(null);
        RefundFailureClosure failure = failureClosures.findByAttemptId(execution.getAttemptId()).orElse(null);
        return new RefundDelayView(execution.getAttemptId(), execution.getRefundId(), execution.getOrderNo(), execution.getPaymentId(),
                execution.getAmount(), execution.getCurrency(), execution.getReceivedAt(), execution.getFirstCancellationPreparedAt(),
                execution.cancellationResumeDeadline(), at, execution.confirmationDelayedSince(at) != null, execution.getStatus(),
                execution.getRefundConfirmedAt(), execution.getRefundConfirmationSource(), execution.unattributed() && closure == null,
                execution.getFailureConfirmedAt(), execution.getAutomaticCancellationStoppedAt(), execution.getCancellationBalanceChangedAt(),
                execution.getActiveCancellationHoldId(), hold == null ? null : hold.getReason(), execution.getReceiptState(), execution.getReceiptObservedAt(),
                execution.getLookupSchedule().getNextAt(), execution.getLookupSchedule().getWakeAt(), execution.getLookupSchedule().getLeaseUntil(),
                execution.getCancellationSchedule().getNextAt(), execution.getCancellationSchedule().getLeaseUntil(),
                closure == null ? null : closure.view(), failure == null ? null : failure.view(),
                execution.getLateCancellationObservedAt(), execution.getLateCancelledAt());
    }
}
