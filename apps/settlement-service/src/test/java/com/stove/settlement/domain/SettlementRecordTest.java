package com.stove.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementRecordTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 7);
    private static final BigDecimal PARTNER_RATE = new BigDecimal("0.3000");

    @Test
    @DisplayName("입점 판매는 수수료를 차감한 금액이 판매자 정산액이다")
    void partnerFee() {
        SettlementRecord record = SettlementRecord.sale("ORD-1", 2L, 1001L, SaleType.PARTNER,
                24000L, PARTNER_RATE, MONTH);

        assertThat(record.getFeeAmount()).isEqualTo(7200L);
        assertThat(record.getNetAmount()).isEqualTo(16800L);
    }

    @Test
    @DisplayName("자체 판매는 수수료가 없다")
    void selfSaleHasNoFee() {
        SettlementRecord record = SettlementRecord.sale("ORD-1", 1L, 1L, SaleType.SELF,
                39000L, BigDecimal.ZERO, MONTH);

        assertThat(record.getFeeAmount()).isZero();
        assertThat(record.getNetAmount()).isEqualTo(39000L);
    }

    @Test
    @DisplayName("환불 역산은 원 매출과 정확히 상계된다")
    void refundOffsetsSale() {
        SettlementRecord sale = SettlementRecord.sale("ORD-1", 2L, 1001L, SaleType.PARTNER,
                24000L, PARTNER_RATE, MONTH);
        SettlementRecord refund = SettlementRecord.refundOf(sale, MONTH);

        assertThat(refund.getRecordType()).isEqualTo(RecordType.REFUND);
        assertThat(sale.getGrossAmount() + refund.getGrossAmount()).isZero();
        assertThat(sale.getFeeAmount() + refund.getFeeAmount()).isZero();
        assertThat(sale.getNetAmount() + refund.getNetAmount()).isZero();
    }

    @Test
    @DisplayName("수수료는 원 단위 반올림으로 계산된다")
    void feeRoundsToWon() {
        SettlementRecord record = SettlementRecord.sale("ORD-1", 3L, 1002L, SaleType.PARTNER,
                12345L, PARTNER_RATE, MONTH);

        // 12345 * 0.3 = 3703.5 → 3704
        assertThat(record.getFeeAmount()).isEqualTo(3704L);
        assertThat(record.getNetAmount()).isEqualTo(8641L);
    }
}
