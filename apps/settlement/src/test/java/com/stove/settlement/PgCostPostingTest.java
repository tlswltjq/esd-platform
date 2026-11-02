package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PgCostPosting;
import com.stove.settlement.core.domain.PgCostTotals;
import com.stove.settlement.core.domain.PgStatementEntry;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.SellerMonthView;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementSale;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PgCostPostingTest {
    private final Instant now=Instant.parse("2026-11-15T03:00:00Z");
    private final PgStatementScope scope=new PgStatementScope("merchant","LIVE","store","channel",true);
    private SettlementSale sale() {
        SettlementSale sale=mock(SettlementSale.class);
        when(sale.getId()).thenReturn(1L);when(sale.getProvider()).thenReturn("PORTONE_LIVE");
        when(sale.getPaymentId()).thenReturn("payment");when(sale.getOrderNo()).thenReturn("order");
        when(sale.getProviderTransactionId()).thenReturn("token");when(sale.getSellerSubject()).thenReturn("seller");
        when(sale.getSettlementMonth()).thenReturn("2026-10");when(sale.getAmount()).thenReturn(10000L);
        return sale;
    }
    private PgStatementEntry entry(String type,long amount,int fee,int vat) {
        return PgStatementEntry.record(scope,new PgStatementRow(type,"pg-"+type,"token","payment",null,amount,fee,vat,fee+vat,"CARD",LocalDate.of(2026,11,5)),now);
    }
    @Test void approvalUsesTheStoredSaleMonthAndKeepsThePgPayoutDateSeparate() {
        PgCostPosting posting=PgCostPosting.record(entry("PAY",10000,-300,-30),sale(),null,1L,now);
        assertThat(posting.getBusinessMonth()).isEqualTo("2026-10");
        assertThat(posting.getEntry().getDueDate()).isEqualTo(LocalDate.of(2026,11,5));
        assertThat(posting.getPolicyId()).isEqualTo("LINKED_BUSINESS_MONTH_V1");
    }
    @Test void returnUsesTheRefundPostingMonthRatherThanTheOriginalSaleMonth() {
        SettlementRefund refund=mock(SettlementRefund.class);
        when(refund.getId()).thenReturn(2L);when(refund.getOrderNo()).thenReturn("order");
        when(refund.getPaymentId()).thenReturn("payment");when(refund.getSellerSubject()).thenReturn("seller");
        when(refund.getProviderTransactionId()).thenReturn("token");when(refund.getSettlementMonth()).thenReturn("2026-11");
        PgCostPosting posting=PgCostPosting.record(entry("REFUND",-10000,200,20),sale(),refund,1L,now);
        assertThat(posting.getBusinessMonth()).isEqualTo("2026-11");assertThat(posting.getRefundRecordId()).isEqualTo(2L);
    }
    @Test void unknownReturnDiffersFromAnExplicitZeroReturnAndNegativeMonthlyCostIsValid() {
        SellerMonthlySettlement month=mock(SellerMonthlySettlement.class);
        when(month.getRecordCount()).thenReturn(0L);when(month.getRefundCount()).thenReturn(1L);
        when(month.getNetPlatformFeeAmount()).thenReturn(-1000L);
        SellerMonthView missing=new SellerMonthView(month,new PgCostTotals(0,0,0,0),new PgCostTotals(1,0,0,0));
        assertThat(missing.pgFeeAmount()).isNull();assertThat(missing.platformNetAmount()).isNull();
        SellerMonthView zero=new SellerMonthView(month,new PgCostTotals(0,0,0,0),new PgCostTotals(1,1,0,0));
        assertThat(zero.pgFeeAmount()).isZero();assertThat(zero.platformNetAmount()).isEqualTo(-1000);
        SellerMonthView returned=new SellerMonthView(month,new PgCostTotals(0,0,0,0),new PgCostTotals(1,1,200,20));
        assertThat(returned.pgFeeAmount()).isEqualTo(-220);assertThat(returned.platformNetAmount()).isEqualTo(-780);
    }
    @Test void aTestPurchaseOrMissingRefundCannotBecomeARealCostPosting() {
        SettlementSale test=sale();when(test.getProvider()).thenReturn("PORTONE_TEST");
        assertThatThrownBy(() -> PgCostPosting.record(entry("PAY",10000,-300,-30),test,null,1L,now)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PgCostPosting.record(entry("REFUND",-10000,0,0),sale(),null,1L,now)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PgCostTotals(1,0,300,0)).isInstanceOf(BusinessException.class);
    }
}
