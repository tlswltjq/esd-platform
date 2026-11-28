package com.stove.payment.adapter.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImmediateApprovalPgGatewayTest {
    private static final Instant NOW = Instant.parse("2026-11-28T01:02:03.123456789Z");
    private final ImmediateApprovalPgGateway gateway = new ImmediateApprovalPgGateway(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void everyChargeIsApprovedForTheRequestedPurchaseWithoutAFee() {
        PgChargeRequest request = request(UUID.randomUUID().toString());

        PgResult result = gateway.charge(request);

        assertThat(result.decision()).isEqualTo(PgResult.Decision.APPROVED);
        assertThat(result.provider()).isEqualTo("SIMULATED");
        assertThat(result.paymentId()).isEqualTo(request.paymentId());
        assertThat(result.orderNo()).isEqualTo(request.orderNo());
        assertThat(result.amount()).isEqualTo(request.amount());
        assertThat(result.currency()).isEqualTo(request.currency());
        assertThat(result.reasonCode()).isNull();
        assertThat(result.reason()).isNull();
        assertThat(result.fee()).isNull();
        assertThat(result.paymentAmounts()).isNull();
        assertThat(result.occurredAt()).isEqualTo(Instant.parse("2026-11-28T01:02:03.123456Z"));
    }

    @Test void aRetryOfTheSamePaymentKeepsItsTransactionIdAndAnotherPaymentGetsADifferentOne() {
        PgChargeRequest first = request(UUID.randomUUID().toString());
        PgChargeRequest other = request(UUID.randomUUID().toString());

        assertThat(gateway.charge(first).transactionId()).isEqualTo(gateway.charge(first).transactionId());
        assertThat(gateway.charge(other).transactionId()).isNotEqualTo(gateway.charge(first).transactionId());
    }

    @Test void theLongestPaymentIdStillYieldsAStorableTransactionId() {
        PgResult result = gateway.charge(request("p".repeat(64)));

        assertThat(result.transactionId()).hasSizeLessThanOrEqualTo(100);
    }

    private static PgChargeRequest request(String paymentId) {
        return new PgChargeRequest(paymentId, UUID.randomUUID().toString(), 10000, "KRW");
    }
}
