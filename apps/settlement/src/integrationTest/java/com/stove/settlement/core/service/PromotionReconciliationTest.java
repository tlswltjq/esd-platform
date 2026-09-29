package com.stove.settlement.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.payload.OrderLine;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.settlement.core.domain.RecordType;
import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.domain.SettlementRecord;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PromotionReconciliationTest {
    @Autowired SettlementRecordService records;
    @Autowired SellerSettlementService closings;
    @Autowired ReconciliationService reconciliation;

    @Test
    @DisplayName("주문에 고정한 수수료율과 정산액이 현재 정산 정책보다 우선한다")
    void orderFeeSnapshotWinsOverCurrentSettlementPolicy() {
        String orderNo = "P-" + UUID.randomUUID();
        OrderLine line = new OrderLine(10L, "Game", 1001L, 8_000, 1,
                10_000, 2_000, 7L, "PLATFORM", 10_000L,
                new BigDecimal("0.0123"), 123L, 9_877L);
        records.recordSale(UUID.randomUUID().toString(), "PaymentCompleted", orderNo, List.of(line));

        SettlementRecord sale = records.findByOrder(orderNo).getFirst();
        assertThat(sale.getFeeRate()).isEqualByComparingTo("0.0123");
        assertThat(sale.getFeeAmount()).isEqualTo(123);
        assertThat(sale.getNetAmount()).isEqualTo(9_877);
        assertThat(sale.getPaidAmount()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("같은 상품의 반복 주문 항목은 매출 원장 한 건으로 합산한다")
    void repeatedProductLinesAreConsolidatedBeforeTheUniqueLedgerWrite() {
        String orderNo = "P-" + UUID.randomUUID();
        Long sellerId = 8_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000L);
        OrderLine line = new OrderLine(10L, "Game", sellerId, 8_000, 1,
                10_000, 2_000, 7L, "PLATFORM");
        records.recordSale(UUID.randomUUID().toString(), "PaymentCompleted", orderNo,
                List.of(line, line));
        var sale = records.findByOrder(orderNo);
        assertThat(sale).hasSize(1);
        assertThat(sale.getFirst().getPaidAmount()).isEqualTo(16_000);
        assertThat(sale.getFirst().getGrossAmount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("마감 뒤 환불은 원래 마감액을 보존하고 조정액으로 대사한다")
    void closedSaleAndLateRefundKeepOriginalClosingAndReconcileAdjustment() {
        String orderNo = "P-" + UUID.randomUUID();
        Long sellerId = 9_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000L);
        OrderLine line = new OrderLine(10L, "Game", sellerId, 8_000, 1,
                10_000, 2_000, 7L, "PLATFORM");

        records.recordSale(UUID.randomUUID().toString(), "PaymentCompleted", orderNo, List.of(line));
        YearMonth month = YearMonth.parse(records.findByOrder(orderNo).getFirst().getSettlementMonth());
        SellerSettlement first = closings.closeSeller(sellerId, month);
        assertThat(first.getBaseGrossAmount()).isEqualTo(10_000);
        assertThat(first.getBaseNetAmount()).isEqualTo(7_000);

        records.recordRefund(UUID.randomUUID().toString(), "PaymentCancelled", orderNo);
        SettlementRecord refund = records.findByOrder(orderNo).stream()
                .filter(r -> r.getRecordType() == RecordType.REFUND).findFirst().orElseThrow();
        assertThat(refund.getAdjustmentForMonth()).isEqualTo(month.toString());
        SellerSettlement after = closings.closeSeller(sellerId, month);
        assertThat(after.getBaseGrossAmount()).isEqualTo(10_000);
        assertThat(after.getBaseNetAmount()).isEqualTo(7_000);
        assertThat(after.getAdjustmentGrossAmount()).isEqualTo(-10_000);
        assertThat(after.getAdjustmentNetAmount()).isEqualTo(-7_000);
        assertThat(after.getNetAmount()).isZero();

        var result = reconciliation.month(month).stream()
                .filter(r -> r.sellerId().equals(sellerId)).findFirst().orElseThrow();
        assertThat(result.balanced()).isTrue();
        assertThat(result.customerPaid()).isZero();
        assertThat(result.platformExpense()).isZero();
        assertThat(result.adjustmentCount()).isEqualTo(1);
    }
}
