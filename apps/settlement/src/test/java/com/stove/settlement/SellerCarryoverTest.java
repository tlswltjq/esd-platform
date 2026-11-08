package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.settlement.core.domain.SellerCarryover;
import com.stove.settlement.core.domain.SellerMonthBalance;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SettlementTotals;
import org.junit.jupiter.api.Test;

/** 결정 121·130: 지급 기준액은 0원 아래로 내리지 않고, 음수는 다음 마감으로 넘긴다. */
class SellerCarryoverTest {
    static SellerMonthlySettlement month(String month, long sale, long refund, boolean closed) {
        SellerMonthlySettlement result=SellerMonthlySettlement.open("usr_seller",month,NOW);
        SettlementTotals sales=sale==0?SettlementTotals.empty():new SettlementTotals(sale,sale/10,sale-sale/10,1);
        SettlementTotals refunds=refund==0?SettlementTotals.empty():new SettlementTotals(refund,refund/10,refund-refund/10,1);
        result.add(sales,NOW); result.addRefund(refunds,NOW);
        if(closed) result.close(sales,refunds,NOW);
        return result;
    }

    @Test void negativeTotalIsCarriedOutAndPayableStaysAtZero() {
        assertThat(SellerMonthBalance.settle(-3600,0)).isEqualTo(new SellerMonthBalance(0L,0L,-3600L));
        assertThat(SellerMonthBalance.settle(2700,-3600)).isEqualTo(new SellerMonthBalance(-3600L,0L,-900L));
        assertThat(SellerMonthBalance.settle(1800,-900)).isEqualTo(new SellerMonthBalance(-900L,900L,0L));
        assertThat(SellerMonthBalance.settle(900,-900)).isEqualTo(new SellerMonthBalance(-900L,0L,0L));
        assertThat(SellerMonthBalance.settle(8100,0)).isEqualTo(new SellerMonthBalance(0L,8100L,0L));
    }
    @Test void positiveCarryInOrOverflowIsRejected() {
        assertThatThrownBy(() -> SellerMonthBalance.settle(100,1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SellerMonthBalance.settle(Long.MIN_VALUE,-1)).isInstanceOf(ArithmeticException.class);
    }
    @Test void openMonthHasNoFixedValues() {
        SellerMonthBalance open=SellerMonthBalance.of(month("2026-10",5000,9000,false),null);
        assertThat(open).isEqualTo(SellerMonthBalance.unsettled());
        assertThat(open.carriedIn()).isNull(); assertThat(open.payable()).isNull(); assertThat(open.carriedOut()).isNull();
    }
    @Test void closedMonthWithoutLedgerIsReadAsCarriedInZero() {
        assertThat(SellerMonthBalance.of(month("2026-10",5000,9000,true),null)).isEqualTo(new SellerMonthBalance(0L,0L,-3600L));
        assertThat(SellerMonthBalance.of(month("2026-09",9000,0,true),null)).isEqualTo(new SellerMonthBalance(0L,8100L,0L));
    }
    @Test void ledgerKeepsTheClosingNetCarryAndSource() {
        SellerMonthlySettlement november=month("2026-11",3000,0,true);
        SellerCarryover row=SellerCarryover.record(november,"2026-10",-3600,NOW);
        assertThat(row.getSellerSubject()).isEqualTo("usr_seller"); assertThat(row.getSettlementMonth()).isEqualTo("2026-11");
        assertThat(row.getNetSellerPayoutAmount()).isEqualTo(2700); assertThat(row.getSourceMonth()).isEqualTo("2026-10");
        assertThat(row.balance()).isEqualTo(new SellerMonthBalance(-3600L,0L,-900L));
        assertThat(SellerMonthBalance.of(november,row)).isEqualTo(row.balance());
        assertThat(SellerCarryover.record(month("2026-09",9000,0,true),null,0,NOW).balance()).isEqualTo(new SellerMonthBalance(0L,8100L,0L));
    }
    @Test void ledgerRejectsOpenMonthsInconsistentSourcesAndOtherMonthsRows() {
        assertThatThrownBy(() -> SellerCarryover.record(month("2026-11",3000,0,false),"2026-10",0,NOW)).isInstanceOf(RuntimeException.class);
        SellerMonthlySettlement november=month("2026-11",3000,0,true);
        assertThatThrownBy(() -> SellerCarryover.record(november,null,-100,NOW)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SellerCarryover.record(november,"2026-11",0,NOW)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SellerCarryover.record(november,"2026-12",0,NOW)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SellerCarryover.record(november,"2026-13",0,NOW)).isInstanceOf(RuntimeException.class);
        SellerCarryover october=SellerCarryover.record(month("2026-10",5000,9000,true),null,0,NOW);
        assertThatThrownBy(() -> SellerMonthBalance.of(november,october)).isInstanceOf(RuntimeException.class);
    }
}
