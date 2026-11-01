package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementTotals;
import com.stove.settlement.core.domain.PgStatementCheck;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementPurchase;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PgStatementDomainTest {
    private final PgStatementScope scope=new PgStatementScope("merchant","LIVE","store","channel",true);
    private final PgStatementPurchase live=new PgStatementPurchase("payment","PORTONE_LIVE","token","store","channel","LIVE",9000,9000L,"token");
    @Test void verifiedLivePurchaseMatchesApprovalAndTheWholeObservedRefund() {
        assertThat(PgStatementCheck.compare(scope,row("PAY","p",9000,-300,-30),live,1,9000).matched()).isTrue();
        assertThat(PgStatementCheck.compare(scope,row("REFUND","r",-9000,300,30),live,1,9000).matched()).isTrue();
    }
    @Test void missingPurchaseMappingAndTestScopeNeverConfirmLiveCosts() {
        PgStatementRow pay=row("PAY","p",9000,-300,-30);
        assertThat(PgStatementCheck.compare(scope,pay,null,1,0).code()).isEqualTo("MISSING_SALE");
        assertThat(PgStatementCheck.compare(new PgStatementScope("merchant","LIVE","store","channel",false),pay,live,1,0).code()).isEqualTo("IDENTITY_UNVERIFIED");
        PgStatementPurchase test=new PgStatementPurchase("payment","PORTONE_TEST","token","store","channel","TEST",9000,null,null);
        assertThat(PgStatementCheck.compare(scope,pay,test,1,0).matched()).isFalse();
        assertThat(PgStatementCheck.compare(new PgStatementScope("merchant","TEST","store","channel",false),pay,live,1,0).code()).isEqualTo("TEST_NOT_SETTLEABLE");
    }
    @Test void incompleteOrExcessRefundCannotBeDeclaredFullyReconciled() {
        PgStatementRow refund=row("REFUND","r",-9000,300,30);
        assertThat(PgStatementCheck.compare(scope,refund,live,1,8000).code()).isEqualTo("REFUND_AMOUNT_PENDING");
        assertThat(PgStatementCheck.compare(scope,refund,live,1,9001).code()).isEqualTo("EXCESS_REFUND");
        assertThat(PgStatementCheck.compare(scope,refund,new PgStatementPurchase("payment","PORTONE_LIVE","token","store","channel","LIVE",9000,null,null),1,9000).code()).isEqualTo("MISSING_REFUND");
    }
    @Test void merchantIdentityAmountAndAmbiguousApprovalsAreRejected() {
        PgStatementRow pay=row("PAY","p",9000,-300,-30);
        assertThat(PgStatementCheck.compare(new PgStatementScope("merchant","LIVE","other","channel",true),pay,live,1,0).code()).isEqualTo("DIFFERENT_MERCHANT");
        assertThat(PgStatementCheck.compare(scope,row("PAY","p",8999,-300,-30),live,1,0).code()).isEqualTo("DIFFERENT_AMOUNT");
        assertThat(PgStatementCheck.compare(scope,pay,live,2,0).code()).isEqualTo("AMBIGUOUS_PAYMENT");
    }
    @Test void signedApprovalAndRefundPreserveFeeAndVatSeparately() {
        PgStatementRow pay=row("PAY","pay",9000,-300,-30);
        PgStatementRow refund=row("REFUND","refund",-9000,300,30);
        assertThat(pay.netAmount()).isEqualTo(8670);
        assertThat(refund.netAmount()).isEqualTo(-8670);
        assertThat(PgStatementTotals.sum(List.of(pay,refund))).isEqualTo(new PgStatementTotals(0,0,0,0));
    }
    @Test void partialOrZeroFeeReturnIsAnObservedValueRatherThanAnAssumption() {
        PgStatementRow refund=row("REFUND","refund",-9000,0,0);
        assertThat(refund.feeVatSum()).isZero();
        assertThat(PgStatementTotals.sum(List.of(row("PAY","pay",9000,-300,-30),refund)).netAmount()).isEqualTo(-330);
    }
    @Test void invalidSignsDatesAndTotalsCannotBecomeEvidence() {
        assertThatThrownBy(() -> row("PAY","pay",9000,300,30)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> row("REFUND","refund",-9000,-300,-30)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PgStatementTotals(9000,-300,-30,-329)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new PgStatementQuery("OTHER",LocalDate.of(2026,10,1))).isInstanceOf(BusinessException.class);
    }
    static PgStatementRow row(String type,String id,long amount,int fee,int vat) {
        return new PgStatementRow(type,id,"token","payment","Title",amount,fee,vat,fee+vat,"CARD",LocalDate.of(2026,10,1));
    }
}
