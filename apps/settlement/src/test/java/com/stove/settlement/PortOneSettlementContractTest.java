package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.adapter.PortOnePaymentHttpClient;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.PgFunding;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.SettlementSale;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PortOneSettlementContractTest {
    @Test void verifiedExternalBenefitKeepsTheOrderAllocationAndUnknownCost() {
        PaidOrder source = order();
        PgFunding funding = new PgFunding(10000, 9976, 24, "store-test", "channel-test");
        PgPayment payment = new PgPayment(source.paymentId(), source.orderNo(), source.amount(), source.currency(),
                new PgEvidence("PORTONE_TEST", "pg-tx", source.occurredAt()), null, funding);
        SettlementSale sale = SettlementSale.record(source, new ProductOwner(1L, "game-1", "usr_seller"), payment,
                new PlatformFeePolicy("PLATFORM_MVP_V1", 1000), "2026-10", NOW);
        assertThat(sale.getSellerPayoutAmount()).isEqualTo(9000);
        assertThat(sale.getPlatformFeeAmount()).isEqualTo(1000);
        assertThat(payment.cost()).isNull();
        assertThat(funding.getPgDiscountAmount()).isEqualTo(24);
    }

    @Test void invalidFundingCannotBecomeAValidPayment() {
        assertThatThrownBy(() -> new PgFunding(9000, 8975, 24, "store-test", "channel-test")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PgFunding(9000, -1, 9001, "store-test", "channel-test")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PgFunding(9000, 8976, 24, "", "channel-test")).isInstanceOf(BusinessException.class);
    }

    @Test void supplierHttpFailureCannotBeTreatedAsAZeroCostOrSuccessfulPayment() {
        PortOnePaymentHttpClient client = new PortOnePaymentHttpClient(RestClient.builder().baseUrl("http://127.0.0.1:1").build(),
                "store-test", "channel-test");
        assertThatThrownBy(() -> client.require("payment-test")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }
}
