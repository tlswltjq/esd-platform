package com.stove.settlement.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.payload.OrderLine;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class PromotionLedgerTest {
    private final YearMonth month = YearMonth.of(2026, 9);
    private final BigDecimal fee = new BigDecimal("0.3000");

    @Test
    void sellerAndPlatformPayTheSameCheckoutButDifferentSettlement() {
        OrderLine sellerLine = new OrderLine(1L, "game", 1001L, 8_000, 1,
                10_000, 2_000, 11L, "SELLER");
        OrderLine platformLine = new OrderLine(1L, "game", 1001L, 8_000, 1,
                10_000, 2_000, 12L, "PLATFORM");

        SettlementRecord seller = SettlementRecord.sale("seller", sellerLine,
                SaleType.PARTNER, fee, month);
        SettlementRecord platform = SettlementRecord.sale("platform", platformLine,
                SaleType.PARTNER, fee, month);

        assertThat(seller.getPaidAmount()).isEqualTo(8_000);
        assertThat(platform.getPaidAmount()).isEqualTo(8_000);
        assertThat(seller.getGrossAmount()).isEqualTo(8_000);
        assertThat(seller.getFeeAmount()).isEqualTo(2_400);
        assertThat(seller.getNetAmount()).isEqualTo(5_600);
        assertThat(seller.getPlatformExpense()).isZero();
        assertThat(platform.getGrossAmount()).isEqualTo(10_000);
        assertThat(platform.getFeeAmount()).isEqualTo(3_000);
        assertThat(platform.getNetAmount()).isEqualTo(7_000);
        assertThat(platform.getPlatformExpense()).isEqualTo(2_000);
    }

    @Test
    void refundReversesFrozenSnapshotAndMarksClosedMonth() {
        OrderLine line = new OrderLine(1L, "game", 1001L, 8_000, 1,
                10_000, 2_000, 12L, "PLATFORM");
        SettlementRecord sale = SettlementRecord.sale("order", line,
                SaleType.PARTNER, fee, month);
        sale.close();
        SettlementRecord refund = SettlementRecord.refundOf(sale, month.plusMonths(1));

        assertThat(refund.getPaidAmount()).isEqualTo(-8_000);
        assertThat(refund.getListAmount()).isEqualTo(-10_000);
        assertThat(refund.getDiscountAmount()).isEqualTo(-2_000);
        assertThat(refund.getPlatformExpense()).isEqualTo(-2_000);
        assertThat(refund.getGrossAmount()).isEqualTo(-10_000);
        assertThat(refund.getFeeAmount()).isEqualTo(-3_000);
        assertThat(refund.getNetAmount()).isEqualTo(-7_000);
        assertThat(refund.getAdjustmentForMonth()).isEqualTo("2026-09");
    }
}
