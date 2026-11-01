package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.order;
import static com.stove.settlement.SettlementFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.FullRefundAllocationPolicy;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.RefundAllocation;
import com.stove.settlement.core.domain.ReverseOriginalFullRefundPolicy;
import com.stove.settlement.core.domain.SettlementSale;
import org.junit.jupiter.api.Test;

class FullRefundAllocationPolicyTest {
    private final FullRefundAllocationPolicy policy = new ReverseOriginalFullRefundPolicy();

    @Test void fullRefundReversesBothOriginalSharesAndPreservesTheirPolicyIdentity() {
        SettlementSale original = sale(9000, "ORIGINAL_TEN_PERCENT_V1", 1000);
        RefundAllocation result = policy.allocate(original);
        assertThat(result.policyId()).isEqualTo("FULL_REFUND_REVERSE_ORIGINAL_V1");
        assertThat(result.orderNo()).isEqualTo(original.getOrderNo());
        assertThat(result.originalPlatformPolicyId()).isEqualTo("ORIGINAL_TEN_PERCENT_V1");
        assertThat(result.paidAmountDelta()).isEqualTo(-9000);
        assertThat(result.platformFeeAmountDelta()).isEqualTo(-900);
        assertThat(result.sellerPayoutAmountDelta()).isEqualTo(-8100);
        assertThat(original.getAmount()).isEqualTo(9000);
        assertThat(original.getPlatformFeeAmount()).isEqualTo(900);
        assertThat(original.getSellerPayoutAmount()).isEqualTo(8100);
        assertThat(policy.allocate(original)).isEqualTo(result);
    }

    @Test void theOriginalRateAndRoundingAreReversedEvenAfterTodaysFeePolicyChanges() {
        SettlementSale original = sale(101, "ORIGINAL_3333_BPS_V1", 3333);
        PlatformFeePolicy current = new PlatformFeePolicy("CURRENT_TWENTY_PERCENT_V2", 2000);
        assertThat(current.fee(original.getAmount())).isEqualTo(20);
        RefundAllocation result = policy.allocate(original);
        assertThat(result.platformFeeAmount()).isEqualTo(34);
        assertThat(result.sellerPayoutAmount()).isEqualTo(67);
        assertThat(result.originalPlatformPolicyId()).isEqualTo("ORIGINAL_3333_BPS_V1");
    }

    @Test void zeroAndFullPlatformSharesRemainExact() {
        RefundAllocation zeroFee = policy.allocate(sale(1, "ZERO_FEE_V1", 0));
        assertThat(zeroFee.platformFeeAmountDelta()).isZero();
        assertThat(zeroFee.sellerPayoutAmountDelta()).isEqualTo(-1);
        RefundAllocation allFee = policy.allocate(sale(1, "FULL_FEE_V1", 10000));
        assertThat(allFee.platformFeeAmountDelta()).isEqualTo(-1);
        assertThat(allFee.sellerPayoutAmountDelta()).isZero();
    }

    @Test void maximumOrderAmountDoesNotOverflowWhenReversed() {
        RefundAllocation result = policy.allocate(sale(Long.MAX_VALUE, "ZERO_FEE_V1", 0));
        assertThat(result.paidAmountDelta()).isEqualTo(-Long.MAX_VALUE);
        assertThat(result.sellerPayoutAmountDelta()).isEqualTo(-Long.MAX_VALUE);
    }

    @Test void anOriginalSaleIsRequired() {
        assertThatThrownBy(() -> policy.allocate(null)).isInstanceOf(BusinessException.class);
    }

    private SettlementSale sale(long amount, String policyId, int rateBps) {
        PaidOrder template = order();
        PaidOrder input = new PaidOrder(template.eventId(), template.occurredAt(), template.orderNo(),
                template.requestEventId(), template.paymentResultEventId(), template.paymentId(), template.buyerSubject(),
                template.productId(), template.productCode(), template.title(), template.contentVersion(), amount, template.currency());
        return SettlementSale.record(input, new ProductOwner(1L, "game-1", "usr_seller"), payment(input, 0),
                new PlatformFeePolicy(policyId, rateBps), "2026-10", NOW);
    }
}
