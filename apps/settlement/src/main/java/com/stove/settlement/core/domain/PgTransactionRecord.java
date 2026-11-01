package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Embedded;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "settlement_pg_transaction") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgTransactionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false, length = 64) private String paymentId;
    @Column(nullable = false, updatable = false, length = 36) private String orderNo;
    @Column(nullable = false, updatable = false) private long amount;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Column(nullable = false, updatable = false, length = 30) private String provider;
    @Column(nullable = false, updatable = false, length = 100) private String providerTransactionId;
    @Column(nullable = false, updatable = false) private Instant providerOccurredAt;
    @Column(nullable = false, updatable = false) private Instant receivedAt;
    @Embedded private PgFunding funding;

    public static PgTransactionRecord record(PgPayment input, Instant now) {
        SettlementValues.require(input != null && SettlementValues.microsecond(now), "PG 거래 접수 근거가 필요합니다.");
        PgTransactionRecord result = new PgTransactionRecord();
        result.paymentId = input.paymentId(); result.orderNo = input.orderNo();
        result.amount = input.amount(); result.currency = input.currency();
        result.provider = input.evidence().provider(); result.providerTransactionId = input.evidence().transactionId();
        result.providerOccurredAt = input.evidence().occurredAt(); result.receivedAt = now;
        result.funding = input.funding();
        return result;
    }
    public PgEvidence evidence() { return new PgEvidence(provider, providerTransactionId, providerOccurredAt); }
    public PgPayment payment(PgCost cost) { return new PgPayment(paymentId, orderNo, amount, currency, evidence(), cost, funding); }
    public void requireSame(PgPayment input) {
        SettlementValues.consistent(input != null && paymentId.equals(input.paymentId()) && orderNo.equals(input.orderNo())
                && amount == input.amount() && currency.equals(input.currency()) && evidence().equals(input.evidence())
                && Objects.equals(funding, input.funding()),
                "이미 확인한 PG 거래 근거와 다릅니다.");
    }
    public void requireSale(SettlementSale sale) {
        SettlementValues.consistent(sale != null && paymentId.equals(sale.getPaymentId()) && orderNo.equals(sale.getOrderNo())
                && amount == sale.getAmount() && currency.equals(sale.getCurrency()) && provider.equals(sale.getProvider())
                && providerTransactionId.equals(sale.getProviderTransactionId()), "매출과 PG 거래 근거가 다릅니다.");
    }
}
