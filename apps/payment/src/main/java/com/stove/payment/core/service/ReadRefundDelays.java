package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationWindow;
import com.stove.payment.core.domain.RefundDelayLookup;
import com.stove.payment.core.domain.RefundDelayQuery;
import com.stove.payment.core.domain.RefundDelayView;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
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
    private final Clock clock;

    public Page<RefundDelayView> search(RefundDelayQuery query) {
        if (query == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Instant at = clock.instant();
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
        if (!"PORTONE_TEST".equals(payment.getProvider()) || execution.cancellationResumeDeadline() == null
                || at.isBefore(execution.cancellationResumeDeadline())) throw new BusinessException(ErrorCode.NOT_FOUND);
        return execution;
    }

    private RefundDelayView view(RefundExecution execution, Instant at) {
        RefundCancellationHold hold = execution.getActiveCancellationHoldId() == null ? null
                : holds.findByHoldId(execution.getActiveCancellationHoldId()).orElse(null);
        return new RefundDelayView(execution.getAttemptId(), execution.getRefundId(), execution.getOrderNo(), execution.getPaymentId(),
                execution.getAmount(), execution.getCurrency(), execution.getReceivedAt(), execution.getFirstCancellationPreparedAt(),
                execution.cancellationResumeDeadline(), at, execution.confirmationDelayedSince(at) != null, execution.getStatus(),
                execution.getRefundConfirmedAt(), execution.getRefundConfirmationSource(), "PG_QUERY_UNATTRIBUTED".equals(execution.getRefundConfirmationSource()),
                execution.getFailureConfirmedAt(), execution.getAutomaticCancellationStoppedAt(), execution.getCancellationBalanceChangedAt(),
                execution.getActiveCancellationHoldId(), hold == null ? null : hold.getReason(), execution.getReceiptState(), execution.getReceiptObservedAt(),
                execution.getLookupSchedule().getNextAt(), execution.getLookupSchedule().getWakeAt(), execution.getLookupSchedule().getLeaseUntil(),
                execution.getCancellationSchedule().getNextAt(), execution.getCancellationSchedule().getLeaseUntil());
    }
}
