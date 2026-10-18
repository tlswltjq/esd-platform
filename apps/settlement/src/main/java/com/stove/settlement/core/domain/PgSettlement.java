package com.stove.settlement.core.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PgSettlement(String provider,String providerSettlementId,String currency,Instant cutoffAt,
        Instant paidAt,String status,long paymentAmountTotal,long pgFeeAmountTotal,long payoutAmount,
        long recordCount,List<PgPayment> items) {
    public PgSettlement {
        SettlementValues.require("SIMULATED".equals(provider) && SettlementValues.text(providerSettlementId,64)
                && "KRW".equals(currency) && "SIMULATED_PAID".equals(status)
                && SettlementValues.microsecond(cutoffAt) && SettlementValues.microsecond(paidAt) && !paidAt.isBefore(cutoffAt),
                "PG 지급 명세가 올바르지 않습니다.");
        SettlementValues.require(items!=null && !items.isEmpty(),"PG 지급 거래가 필요합니다.");
        items=List.copyOf(items);
        Set<String> payments=new HashSet<String>(); Set<String> transactions=new HashSet<String>(); Set<String> orders=new HashSet<String>();
        long amount=0; long fee=0;
        for(PgPayment item:items) {
            SettlementValues.require(provider.equals(item.cost().getProvider()) && currency.equals(item.currency())
                    && !item.cost().getProviderOccurredAt().isAfter(cutoffAt)
                    && payments.add(item.paymentId()) && transactions.add(item.cost().getProviderTransactionId()) && orders.add(item.orderNo()),
                    "PG 명세 항목이 중복되거나 지급 범위와 다릅니다.");
            amount=Math.addExact(amount,item.amount()); fee=Math.addExact(fee,item.cost().getPgFeeAmount());
        }
        SettlementValues.require(recordCount==items.size() && amount==paymentAmountTotal && fee==pgFeeAmountTotal
                && payoutAmount==Math.subtractExact(amount,fee),"PG 명세 합계가 다릅니다.");
    }
}
