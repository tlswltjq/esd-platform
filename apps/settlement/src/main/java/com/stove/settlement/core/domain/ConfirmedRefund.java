package com.stove.settlement.core.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

public record ConfirmedRefund(String eventId, Instant occurredAt, String refundId, String attemptId,
        String requestEventId, String orderNo, String paymentId, String buyerSubject, long amount, String currency,
        String source, String providerTransactionId, List<String> cancellationIds) {
    public ConfirmedRefund {
        SettlementValues.require(SettlementValues.text(eventId, 64) && SettlementValues.microsecond(occurredAt)
                && SettlementValues.uuid(refundId) && SettlementValues.uuid(attemptId) && SettlementValues.text(requestEventId, 64),
                "환불 금융 확인의 식별자·시각이 올바르지 않습니다.");
        SettlementValues.require(SettlementValues.uuid(orderNo) && SettlementValues.text(paymentId, 64)
                && SettlementValues.text(buyerSubject, 100) && amount > 0 && "KRW".equals(currency), "환불 원구매가 올바르지 않습니다.");
        SettlementValues.require(("PG_RESPONSE".equals(source) || "PG_QUERY_UNATTRIBUTED".equals(source))
                && SettlementValues.text(providerTransactionId, 100) && cancellationIds != null && !cancellationIds.isEmpty()
                && cancellationIds.stream().allMatch(value -> SettlementValues.text(value, 100))
                && new HashSet<>(cancellationIds).size() == cancellationIds.size(), "검증한 전액 취소 근거가 필요합니다.");
        cancellationIds = cancellationIds.stream().sorted().toList();
    }
    public void requireSale(SettlementSale sale) {
        SettlementValues.consistent(sale != null && orderNo.equals(sale.getOrderNo()) && paymentId.equals(sale.getPaymentId())
                && buyerSubject.equals(sale.getBuyerSubject()) && amount == sale.getAmount() && currency.equals(sale.getCurrency())
                && providerTransactionId.equals(sale.getProviderTransactionId()), "환불 금융 확인과 원매출이 다릅니다.");
    }
}
