package com.stove.payment.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationHoldLookup;
import com.stove.payment.core.domain.RefundCancellationHoldQuery;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationHoldSummary;
import com.stove.payment.core.domain.RefundCancellationHoldView;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadRefundCancellationHolds {
    private final PaymentRepository payments;
    private final RefundExecutionRepository executions;
    private final RefundCancellationHoldRepository holds;
    private final Clock clock;

    /** List rows carry only the type preview and length; the full type is in {@link #read} (PR #168 R5). */
    public Page<RefundCancellationHoldView> search(RefundCancellationHoldQuery query) {
        if (query == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Instant at = clock.instant();
        return holds.findForAdministration(query.activeOnly(), PageRequest.of(query.page(), query.size(), Sort.by("observedAt", "id")))
                .map(hold -> view(hold, null, at));
    }

    public RefundCancellationHoldView read(RefundCancellationHoldLookup query) {
        if (query == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        RefundCancellationHold hold = holds.findByHoldId(query.holdId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return view(RefundCancellationHoldSummary.of(hold), hold.getRejectionType(), clock.instant());
    }

    private RefundCancellationHoldView view(RefundCancellationHoldSummary hold, String rejectionType, Instant at) {
        RefundExecution execution = executions.findByAttemptId(hold.attemptId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Payment payment = payments.findByPaymentId(execution.getPaymentId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"PORTONE_TEST".equals(payment.getProvider())) throw new BusinessException(ErrorCode.NOT_FOUND);
        boolean resumable = hold.activated() && hold.resumeRequestId() == null && !at.isBefore(hold.observedAt())
                && execution.canResumeCancellation(hold.holdId(), at);
        return new RefundCancellationHoldView(hold.holdId(), hold.attemptId(), execution.getRefundId(), execution.getOrderNo(),
                execution.getPaymentId(), execution.getAmount(), execution.getCurrency(), hold.reason(), hold.rejectionStatus(), rejectionType,
                hold.rejectionTypePreview(), hold.rejectionTypeLength(), hold.observedAt(), hold.activated(),
                execution.isCurrentCancellationHold(hold.holdId()), resumable, execution.getFirstCancellationPreparedAt(),
                execution.cancellationResumeDeadline(), execution.getStatus(), execution.getRefundConfirmedAt(),
                hold.receipt(), hold.operatorSubject(), hold.resumeNote());
    }
}
