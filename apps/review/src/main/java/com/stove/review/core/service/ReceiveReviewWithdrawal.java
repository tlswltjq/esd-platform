package com.stove.review.core.service;

import com.stove.common.kafka.contract.payload.ProjectReviewWithdrawalResolvedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.review.core.domain.ProjectReview;
import com.stove.review.core.domain.ProjectReviewRepository;
import com.stove.review.core.domain.ReviewRequestGateRepository;
import com.stove.review.core.domain.ReviewWithdrawal;
import com.stove.review.core.domain.ReviewWithdrawalInput;
import com.stove.review.core.domain.ReviewWithdrawalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceiveReviewWithdrawal {
    public static final String CONSUMER_GROUP = ReceiveProjectReview.CONSUMER_GROUP;
    private final ReviewRequestGateRepository gates;
    private final ReviewWithdrawalRepository withdrawals;
    private final ProjectReviewRepository reviews;
    private final ProcessedEventGuard guard;
    private final OutboxRecorder outbox;

    public void execute(ReviewWithdrawalInput input) {
        Long requestId = input.request().requestId();
        gates.ensure(requestId);
        gates.lock(requestId).orElseThrow();
        ReviewWithdrawal withdrawal = withdrawals.findById(input.request().withdrawalId()).orElse(null);
        if (withdrawal != null) {
            withdrawal.requireSame(input.request());
            if (withdrawal.isResolved()) return;
        }
        boolean first = guard.firstDelivery(input.eventId(), CONSUMER_GROUP, input.eventType());
        if (withdrawal == null) {
            if (!first) throw new BusinessException(ErrorCode.CONFLICT, "철회 명령 근거가 없습니다.");
            withdrawal = withdrawals.save(ReviewWithdrawal.receive(input));
        }
        ProjectReview review = reviews.lock(requestId).orElse(null);
        if (review == null) return;
        outbox.record("ProjectReview", review.getProductCode(),
                ProjectReviewWithdrawalResolvedEvent.of(withdrawal.resolve(review)));
    }
}
