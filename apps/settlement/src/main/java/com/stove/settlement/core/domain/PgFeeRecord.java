package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "settlement_pg_fee") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgFeeRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false, length = 64) private String paymentId;
    @Embedded private PgCost cost;
    @Column(nullable = false, updatable = false) private Instant receivedAt;
    @Column(nullable = false, updatable = false, length = 100) private String recordedBy;

    public static PgFeeRecord record(PgPayment payment, Instant now, String operator) {
        SettlementValues.require(payment != null && payment.cost() != null && SettlementValues.microsecond(now) && SettlementValues.text(operator,100),
                "확인한 PG 비용 근거가 필요합니다.");
        PgFeeRecord result = new PgFeeRecord();
        result.paymentId = payment.paymentId(); result.cost = payment.cost(); result.receivedAt = now; result.recordedBy = operator;
        return result;
    }
    public void requireSame(PgPayment payment) {
        SettlementValues.consistent(paymentId.equals(payment.paymentId()) && cost.equals(payment.cost()),
                "이미 확인한 PG 비용과 다릅니다.");
    }
}
