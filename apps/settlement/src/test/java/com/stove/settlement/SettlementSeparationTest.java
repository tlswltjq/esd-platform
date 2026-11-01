package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthView;
import com.stove.settlement.core.domain.PgFeeTotals;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleView;
import com.stove.settlement.core.domain.SettlementTotals;
import org.junit.jupiter.api.Test;

class SettlementSeparationTest {
    @Test
    void verifiedPaymentWithoutFeeStillProducesTheOriginalSellerAllocation() {
        PaidOrder source = order();
        PgEvidence evidence = new PgEvidence("SIMULATED", "pg-approved-1", source.occurredAt().minusSeconds(1));
        PgPayment payment = new PgPayment(source.paymentId(), source.orderNo(), source.amount(), source.currency(), evidence, null);
        SettlementSale sale = SettlementSale.record(source, new ProductOwner(1L, "game-1", "usr_seller"), payment,
                new PlatformFeePolicy("PLATFORM_MVP_V1", 1000), "2026-10", NOW);

        assertThat(sale.source()).isEqualTo(source);
        assertThat(sale.totals()).isEqualTo(new SettlementTotals(10000, 1000, 9000, 1));
        assertThat(new SettlementSaleView(sale, evidence, null).platformNetAmount()).isNull();
    }

    @Test
    void lateFeeChangesOnlyThePgViewNotTheSellerFacts() {
        PaidOrder source = order();
        PgEvidence evidence = new PgEvidence("SIMULATED", "pg-approved-2", source.occurredAt().minusSeconds(1));
        SettlementSale sale = SettlementSale.record(source, new ProductOwner(1L, "game-1", "usr_seller"),
                new PgPayment(source.paymentId(), source.orderNo(), source.amount(), source.currency(), evidence, null),
                new PlatformFeePolicy("PLATFORM_MVP_V1", 1000), "2026-10", NOW);
        SellerMonthlySettlement month = SellerMonthlySettlement.open("usr_seller", "2026-10", NOW);
        month.add(sale.totals(), NOW);
        SettlementTotals before = month.totals();
        PgCost cost = new PgCost(evidence.provider(), evidence.transactionId(), evidence.occurredAt(), "reported", 300, 300);

        assertThat(new SettlementSaleView(sale, evidence, cost).platformNetAmount()).isEqualTo(700);
        assertThat(new SellerMonthView(month, new PgFeeTotals(1, 0, 0)).pgFeeAmount()).isNull();
        assertThat(new SellerMonthView(month, new PgFeeTotals(1, 1, 300)).pgFeeAmount()).isEqualTo(300);
        assertThat(month.totals()).isEqualTo(before);
        assertThat(month.getSellerPayoutAmount()).isEqualTo(9000);
        assertThat(sale.source()).isEqualTo(source);
    }

    @Test
    void feeEvidenceCannotBeAttachedToAnotherTransaction() {
        PaidOrder source = order();
        PgEvidence evidence = new PgEvidence("SIMULATED", "approved", source.occurredAt());
        PgCost another = new PgCost("SIMULATED", "different", source.occurredAt(), "reported", 300, 300);
        assertThatThrownBy(() -> new PgPayment(source.paymentId(), source.orderNo(), source.amount(), source.currency(), evidence, another))
                .isInstanceOf(BusinessException.class);
    }
}
