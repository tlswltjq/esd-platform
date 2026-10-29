package com.stove.payment.api.controller.dto;

import com.stove.payment.core.domain.PaymentFailureHistory;
import java.time.Instant;
import java.util.List;

public record PaymentAttemptFailurePage(List<PaymentAttemptFailureResponse> content, int page, int size, long totalElements, int totalPages,
        Instant checkedAt, boolean collectionPending) {
    public static PaymentAttemptFailurePage from(PaymentFailureHistory history) {
        return new PaymentAttemptFailurePage(history.attempts().map(PaymentAttemptFailureResponse::from).getContent(),
                history.attempts().getNumber(), history.attempts().getSize(), history.attempts().getTotalElements(), history.attempts().getTotalPages(), history.checkedAt(), history.collectionPending());
    }
}
