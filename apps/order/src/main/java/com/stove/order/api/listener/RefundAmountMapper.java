package com.stove.order.api.listener;

import com.stove.common.kafka.contract.payload.RefundAmountsSnapshot;
import com.stove.order.core.domain.ProviderPaymentAmounts;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.ReportedRefundAmounts;
import com.stove.order.core.domain.ReportedRefundCancellation;
import java.util.Objects;

final class RefundAmountMapper {
    private RefundAmountMapper() { }

    static ReportedRefundAmounts from(RefundAmountsSnapshot snapshot, long orderAmount) {
        if (snapshot == null) return null;
        if (snapshot.cancellations() == null || snapshot.cancellations().stream().anyMatch(Objects::isNull))
            throw new RefundCompletionRejected("반환 금액 상세의 취소 근거가 없습니다.");
        return new ReportedRefundAmounts(orderAmount,
                ProviderPaymentAmounts.fromNullable(snapshot.pgPaidAmount(), snapshot.pgDiscountAmount()),
                snapshot.cancellations().stream().map(row -> new ReportedRefundCancellation(
                        row.cancellationId(), row.totalAmount(), row.pointAmount())).toList(), snapshot.providerCancelledAt());
    }
}
