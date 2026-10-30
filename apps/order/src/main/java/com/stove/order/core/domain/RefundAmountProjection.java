package com.stove.order.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A read copy of verified Payment facts, separate from the refund's financial completion. */
@Embeddable @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundAmountProjection {
    private Long refundPgPaidAmount;
    private Long refundPgDiscountAmount;
    private Long confirmedCancellationAmount;
    private Long confirmedPointRefundAmount;
    private Instant refundAmountsReportedAt;
    private Instant refundProviderCancelledAt;
    @Column(length = 30) private String refundAmountsSource;
    @Column(length = 64) private String refundAmountsCancellationIdsHash;

    public static RefundAmountProjection merge(RefundAmountProjection current, Order order, RefundPaymentConfirmation input) {
        ReportedRefundAmounts reported = input.amountDetails();
        if (reported == null) return current;
        ProviderPaymentAmounts orderAmounts = ProviderPaymentAmounts.fromNullable(order.getPgPaidAmount(), order.getPgDiscountAmount());
        ProviderPaymentAmounts incoming = reported.paymentAmounts();
        if (orderAmounts != null && incoming != null && !orderAmounts.equals(incoming))
            throw new RefundCompletionRejected("반환 상세의 승인 금액과 주문의 승인 사본이 다릅니다.");
        String idsHash = reported.cancellationIdsFingerprint();
        Long points = reported.confirmedPointAmount();
        if (current != null) {
            ProviderPaymentAmounts previous = ProviderPaymentAmounts.fromNullable(current.refundPgPaidAmount, current.refundPgDiscountAmount);
            if (!Objects.equals(current.confirmedCancellationAmount, reported.orderAmount())
                    || !current.refundAmountsCancellationIdsHash.equals(idsHash)
                    || (previous != null && incoming != null && !previous.equals(incoming))
                    || (current.confirmedPointRefundAmount != null && points != null && !current.confirmedPointRefundAmount.equals(points))
                    || (current.refundProviderCancelledAt != null && reported.providerCancelledAt() != null
                        && !current.refundProviderCancelledAt.equals(reported.providerCancelledAt())))
                throw new RefundCompletionRejected("확인한 반환 금액 상세를 다른 근거로 바꿀 수 없습니다.");
        }
        RefundAmountProjection next = new RefundAmountProjection();
        next.refundPgPaidAmount = current == null ? null : current.refundPgPaidAmount;
        next.refundPgDiscountAmount = current == null ? null : current.refundPgDiscountAmount;
        next.confirmedPointRefundAmount = current == null ? null : current.confirmedPointRefundAmount;
        next.refundProviderCancelledAt = current == null ? null : current.refundProviderCancelledAt;
        boolean enriched = current == null;
        if (next.refundPgPaidAmount == null && incoming != null) {
            next.refundPgPaidAmount = incoming.paidAmount(); next.refundPgDiscountAmount = incoming.discountAmount(); enriched = true;
        }
        if (next.confirmedPointRefundAmount == null && points != null) { next.confirmedPointRefundAmount = points; enriched = true; }
        if (next.refundProviderCancelledAt == null && reported.providerCancelledAt() != null) {
            next.refundProviderCancelledAt = reported.providerCancelledAt(); enriched = true;
        }
        if (!enriched) return current;
        next.confirmedCancellationAmount = reported.orderAmount(); next.refundAmountsCancellationIdsHash = idsHash;
        boolean latest = current == null || !input.occurredAt().isBefore(current.refundAmountsReportedAt);
        next.refundAmountsReportedAt = latest ? input.occurredAt() : current.refundAmountsReportedAt;
        next.refundAmountsSource = latest ? input.source() : current.refundAmountsSource;
        return next;
    }
}
