package com.stove.settlement.core.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "settlement_refund") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementRefund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false, length = 64) private String eventId;
    @Column(nullable = false, updatable = false) private Instant confirmedAt;
    @Column(nullable = false, updatable = false, length = 36) private String refundId;
    @Column(nullable = false, updatable = false, length = 36) private String attemptId;
    @Column(nullable = false, updatable = false, length = 64) private String requestEventId;
    @Column(nullable = false, updatable = false, length = 36) private String orderNo;
    @Column(nullable = false, updatable = false, length = 64) private String paymentId;
    @Column(nullable = false, updatable = false, length = 100) private String buyerSubject;
    @Column(nullable = false, updatable = false, length = 100) private String sellerSubject;
    @Column(nullable = false, updatable = false) private Long productId;
    @Column(nullable = false, updatable = false, length = 50) private String productCode;
    @Column(nullable = false, updatable = false, length = 200) private String title;
    @Column(nullable = false, updatable = false) private long contentVersion;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Column(nullable = false, updatable = false, length = 30) private String source;
    @Column(nullable = false, updatable = false, length = 30) private String provider;
    @Column(nullable = false, updatable = false, length = 100) private String providerTransactionId;
    @Column(nullable = false, updatable = false) private Instant supplierCancelledAt;
    @Column(nullable = false, updatable = false, length = 100) private String allocationPolicyId;
    @Column(nullable = false, updatable = false, length = 100) private String originalPlatformPolicyId;
    @Column(nullable = false, updatable = false) private long platformFeeAmount;
    @Column(nullable = false, updatable = false) private long sellerPayoutAmount;
    @Column(nullable = false, updatable = false, length = 100) private String monthPolicyId;
    @Column(nullable = false, updatable = false, length = 7) private String refundMonth;
    @Column(nullable = false, updatable = false, length = 7) private String settlementMonth;
    @Column(nullable = false, updatable = false, length = 100) private String monthReason;
    @Column(nullable = false, updatable = false) private Instant receivedAt;
    @ElementCollection @CollectionTable(name = "settlement_refund_cancellation", joinColumns = @JoinColumn(name = "refund_record_id"))
    @OrderBy("cancellationId ASC") private List<PgRefundCancellation> cancellations = new ArrayList<>();

    public static SettlementRefund record(ConfirmedRefund input, SettlementSale sale, PgRefund proof,
            RefundAllocation allocation, RefundMonthSelection selection) {
        SettlementValues.require(input != null && sale != null && proof != null && allocation != null && selection != null,
                "환불 원장 근거가 필요합니다.");
        input.requireSale(sale); proof.requireConfirmation(input);
        selection.requireSame(input.refundId(), input.orderNo(), input.occurredAt());
        SettlementValues.consistent(allocation.orderNo().equals(sale.getOrderNo()) && allocation.amount() == sale.getAmount()
                && allocation.originalPlatformPolicyId().equals(sale.getPlatformPolicyId())
                && allocation.platformFeeAmount() == sale.getPlatformFeeAmount() && allocation.sellerPayoutAmount() == sale.getSellerPayoutAmount(),
                "환불 배분과 원매출이 다릅니다.");
        SettlementValues.consistent(proof.original().evidence().provider().equals(sale.getProvider()), "환불 공급자가 다릅니다.");
        SettlementRefund result = new SettlementRefund();
        result.eventId=input.eventId(); result.confirmedAt=input.occurredAt(); result.refundId=input.refundId();
        result.attemptId=input.attemptId(); result.requestEventId=input.requestEventId(); result.orderNo=input.orderNo();
        result.paymentId=input.paymentId(); result.buyerSubject=input.buyerSubject(); result.amount=input.amount(); result.currency=input.currency();
        result.sellerSubject=sale.getSellerSubject(); result.productId=sale.getProductId(); result.productCode=sale.getProductCode();
        result.title=sale.getTitle(); result.contentVersion=sale.getContentVersion(); result.source=input.source();
        result.provider=sale.getProvider(); result.providerTransactionId=input.providerTransactionId(); result.supplierCancelledAt=proof.cancelledAt();
        result.allocationPolicyId=allocation.policyId(); result.originalPlatformPolicyId=allocation.originalPlatformPolicyId();
        result.platformFeeAmount=allocation.platformFeeAmount(); result.sellerPayoutAmount=allocation.sellerPayoutAmount();
        result.monthPolicyId=selection.policyId(); result.refundMonth=selection.refundMonth(); result.settlementMonth=selection.settlementMonth();
        result.monthReason=selection.reason(); result.receivedAt=selection.firstReceivedAt(); result.cancellations.addAll(proof.cancellations());
        return result;
    }
    public SettlementTotals totals() { return new SettlementTotals(amount, platformFeeAmount, sellerPayoutAmount, 1); }
    public ConfirmedRefund confirmation() {
        return new ConfirmedRefund(eventId, confirmedAt, refundId, attemptId, requestEventId, orderNo, paymentId, buyerSubject,
                amount, currency, source, providerTransactionId, cancellations.stream().map(PgRefundCancellation::getCancellationId).toList());
    }
    public RefundMonthSelection selection() {
        return new RefundMonthSelection(monthPolicyId, refundId, orderNo, confirmedAt, receivedAt, refundMonth, settlementMonth, monthReason);
    }
    public void requireSame(ConfirmedRefund input) { SettlementValues.consistent(confirmation().equals(input), "최초 환불 원장과 금융 확인이 다릅니다."); }
    public List<PgRefundCancellation> getCancellations() { return List.copyOf(cancellations); }
}
