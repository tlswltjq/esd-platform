package com.stove.settlement.core.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Independently observed full cancellation. It says nothing about PG fee reimbursement. */
public record PgRefund(PgPayment original, List<PgRefundCancellation> cancellations, Instant cancelledAt) {
    public PgRefund {
        SettlementValues.require(original != null && cancellations != null && !cancellations.isEmpty()
                && SettlementValues.microsecond(cancelledAt), "전액 취소 조회 근거가 필요합니다.");
        Set<String> ids = new HashSet<>(); long total = 0;
        for (PgRefundCancellation cancellation : cancellations) {
            SettlementValues.require(cancellation != null && original.evidence().provider().equals(cancellation.getProvider())
                    && ids.add(cancellation.getCancellationId()), "취소 공급자·식별자가 올바르지 않습니다.");
            total = Math.addExact(total, cancellation.getAmount());
        }
        SettlementValues.require(total == original.amount(), "공급자가 원결제 전액 취소를 확인하지 않았습니다.");
        cancellations = cancellations.stream().sorted(Comparator.comparing(PgRefundCancellation::getCancellationId)).toList();
    }
    public void requireOriginal(PgPayment expected) {
        SettlementValues.consistent(expected != null && original.paymentId().equals(expected.paymentId())
                && original.orderNo().equals(expected.orderNo()) && original.amount() == expected.amount()
                && original.currency().equals(expected.currency()) && original.evidence().equals(expected.evidence())
                && Objects.equals(original.funding(), expected.funding()), "취소 조회와 검증된 원승인 근거가 다릅니다.");
    }
    public void requireConfirmation(ConfirmedRefund input) {
        SettlementValues.consistent(input != null && original.paymentId().equals(input.paymentId())
                && original.orderNo().equals(input.orderNo()) && original.amount() == input.amount()
                && original.currency().equals(input.currency()) && original.evidence().transactionId().equals(input.providerTransactionId())
                && cancellations.stream().map(PgRefundCancellation::getCancellationId).toList().equals(input.cancellationIds()),
                "금융 확인 사건과 공급자 취소 내역이 다릅니다.");
    }
}
