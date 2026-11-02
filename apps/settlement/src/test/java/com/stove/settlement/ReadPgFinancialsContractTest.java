package com.stove.settlement;

import static com.stove.settlement.PgCostContractFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.settlement.api.controller.dto.AdminMonthResponse;
import com.stove.settlement.core.domain.PgCostPostingRepository;
import com.stove.settlement.core.domain.PgFeeGroup;
import com.stove.settlement.core.domain.PgFeeRepository;
import com.stove.settlement.core.domain.PgPostedCostGroup;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SellerMonthView;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SettlementTotals;
import com.stove.settlement.core.service.ReadPgFinancials;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Explicit repository aggregate projections; SQL aggregation is a separate integration concern. */
class ReadPgFinancialsContractTest {
    private final PgFeeRepository legacy=mock(PgFeeRepository.class);
    private final PgCostPostingRepository reported=mock(PgCostPostingRepository.class);
    private final ReadPgFinancials read=new ReadPgFinancials(mock(PgTransactionRepository.class),legacy,reported);
    @BeforeEach void emptyProjections() {
        when(legacy.summarize(anyList(),anyList())).thenReturn(List.of());
        when(reported.summarize(anyList(),anyList())).thenReturn(List.of());
    }
    @Test void aLateReportedCostChangesTheFinancialViewOfAClosedMonthWhileClosingFactsStayFixed() {
        SellerMonthlySettlement month=SellerMonthlySettlement.open("seller","2026-10",NOW);
        month.add(new SettlementTotals(10000,1000,9000,1),NOW);month.close(month.totals(),NOW);
        assertThat(read.month(month).platformNetAmount()).isNull();
        when(reported.summarize(anyList(),anyList())).thenReturn(List.of(new PgPostedCostGroup("seller","2026-10","PAY",1,0,-300,-30)));
        AdminMonthResponse response=AdminMonthResponse.from(read.month(month));
        assertThat(response.pgFeeAmount()).isEqualTo(330);assertThat(response.platformNetAmount()).isEqualTo(670);
        assertThat(response.salePgFeeVatAmount()).isEqualTo(30);assertThat(response.month().closedAt()).isEqualTo(NOW);
        assertThat(month.totals()).isEqualTo(new SettlementTotals(10000,1000,9000,1));assertThat(month.isClosed()).isTrue();
    }
    @Test void refundOnlyMonthDistinguishesMissingZeroAndPartialFeeReturns() {
        SellerMonthlySettlement month=SellerMonthlySettlement.open("seller","2026-11",NOW);
        month.addRefund(new SettlementTotals(10000,1000,9000,1),NOW);
        AdminMonthResponse missing=AdminMonthResponse.from(read.month(month));
        assertThat(missing.refundPgFeeReturnAmount()).isNull();assertThat(missing.refundPgFeeVatReturnAmount()).isNull();
        when(reported.summarize(anyList(),anyList())).thenReturn(List.of(new PgPostedCostGroup("seller","2026-11","REFUND",1,1,0,0)));
        assertThat(read.month(month).platformNetAmount()).isEqualTo(-1000);
        when(reported.summarize(anyList(),anyList())).thenReturn(List.of(new PgPostedCostGroup("seller","2026-11","REFUND",1,1,200,20)));
        AdminMonthResponse returned=AdminMonthResponse.from(read.month(month));
        assertThat(returned.pgFeeAmount()).isEqualTo(-220);assertThat(returned.platformNetAmount()).isEqualTo(-780);
        assertThat(returned.refundPgFeeVatReturnAmount()).isEqualTo(20);assertThat(returned.pendingPgCostCount()).isZero();
    }
    @Test void aPartialProjectionExposesItsConfirmedSubtotalAndKeepsTheFinalNetUnknown() {
        SellerMonthlySettlement month=SellerMonthlySettlement.open("seller","2026-10",NOW);
        month.add(new SettlementTotals(20000,2000,18000,2),NOW);
        when(reported.summarize(anyList(),anyList())).thenReturn(List.of(new PgPostedCostGroup("seller","2026-10","PAY",1,0,-300,-30)));
        SellerMonthView view=read.month(month);assertThat(view.pgCostStatus()).isEqualTo("PARTIAL");
        assertThat(view.confirmedNetCost()).isEqualTo(330);assertThat(view.pendingPgCostCount()).isEqualTo(1);
        assertThat(view.pgFeeAmount()).isNull();assertThat(view.platformNetAmount()).isNull();
    }
    @Test void legacySimulatedCostsAreCombinedWithoutInventingOrCopyingReportedRows() {
        SellerMonthlySettlement month=SellerMonthlySettlement.open("seller","2026-10",NOW);
        month.add(new SettlementTotals(20000,2000,18000,2),NOW);
        when(legacy.summarize(anyList(),anyList())).thenReturn(List.of(new PgFeeGroup("seller","2026-10",2,1,300)));
        when(reported.summarize(anyList(),anyList())).thenReturn(List.of(new PgPostedCostGroup("seller","2026-10","PAY",1,0,-200,-20)));
        assertThat(read.month(month).pgFeeAmount()).isEqualTo(520);assertThat(read.month(month).platformNetAmount()).isEqualTo(1480);
    }
}
