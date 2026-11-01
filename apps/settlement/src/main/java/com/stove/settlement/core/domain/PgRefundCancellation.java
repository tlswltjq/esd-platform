package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable @Getter @EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PgRefundCancellation {
    @Column(nullable = false, updatable = false, length = 30) private String provider;
    @Column(nullable = false, updatable = false, length = 100) private String cancellationId;
    @Column(name = "cancelled_amount", nullable = false, updatable = false) private long amount;
    @Column(updatable = false) private Instant cancelledAt;

    public PgRefundCancellation(String provider, String cancellationId, long amount, Instant cancelledAt) {
        SettlementValues.require(("SIMULATED".equals(provider) || "PORTONE_TEST".equals(provider))
                && SettlementValues.text(cancellationId, 100) && amount > 0
                && (cancelledAt == null || SettlementValues.microsecond(cancelledAt)), "공급자 취소 근거가 올바르지 않습니다.");
        this.provider = provider; this.cancellationId = cancellationId; this.amount = amount; this.cancelledAt = cancelledAt;
    }
}
