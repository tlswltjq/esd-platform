package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.order;
import static com.stove.settlement.SettlementFixtures.payment;
import static com.stove.settlement.SettlementFixtures.report;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgSettlement;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.SettlementMonth;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementTotals;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementDomainTest {
    @Test void pgExpenseOnlyChangesPlatformShareIncludingNegativeRemainder() {
        PaidOrder order=order();
        for(long fee:new long[]{300,500,1200}) {
            SettlementSale sale=SettlementSale.record(order,new ProductOwner(1L,"game-1","usr_seller"),payment(order,fee),
                    new PlatformFeePolicy("PLATFORM_MVP_V1",1000),"2026-10",NOW);
            assertThat(sale.getSellerPayoutAmount()).isEqualTo(9000);
            assertThat(sale.getPlatformNetAmount()).isEqualTo(1000-fee);
            assertThat(sale.totals().paidAmount()).isEqualTo(10000);
        }
    }
    @Test void halfUpAndOverflowAreExplicit() {
        assertThat(new PlatformFeePolicy("test",100).fee(50)).isEqualTo(1);
        assertThat(new PlatformFeePolicy("test",0).fee(Long.MAX_VALUE)).isZero();
        assertThatThrownBy(() -> new SettlementTotals(Long.MAX_VALUE,0,Long.MAX_VALUE,0,0,1)
                .plus(new SettlementTotals(1,0,1,0,0,1))).isInstanceOf(ArithmeticException.class);
    }
    @Test void reportRejectsDuplicateItemsAndChangedTotals() {
        PgPayment payment=payment(order(),300); PgSettlement valid=report(List.of(payment));
        assertThatThrownBy(() -> report(List.of(payment,payment))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PgSettlement(valid.provider(),valid.providerSettlementId(),valid.currency(),valid.cutoffAt(),
                valid.paidAt(),valid.status(),valid.paymentAmountTotal(),valid.pgFeeAmountTotal(),1,1,valid.items())).isInstanceOf(BusinessException.class);
    }
    @Test void monthUsesKoreaBoundaryAndRejectsMalformedInput() {
        assertThat(SettlementMonth.at(Instant.parse("2026-09-30T14:59:59Z")).value()).isEqualTo("2026-09");
        assertThat(SettlementMonth.at(Instant.parse("2026-09-30T15:00:00Z")).value()).isEqualTo("2026-10");
        assertThatThrownBy(() -> new SettlementMonth("2026-13")).isInstanceOf(BusinessException.class);
    }
}
