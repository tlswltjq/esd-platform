package com.stove.settlement.core.domain;

public record PgStatementCheck(String code,String paymentId) {
    public PgStatementCheck {
        SettlementValues.require(SettlementValues.text(code,40) && (paymentId==null || SettlementValues.text(paymentId,64)),"명세 대사 상태가 올바르지 않습니다.");
    }
    public boolean matched() {return "MATCHED_REPORTED".equals(code);}
    public boolean mismatch() {return code.startsWith("DIFFERENT_") || "AMBIGUOUS_PAYMENT".equals(code) || "EXCESS_REFUND".equals(code);}
    public static PgStatementCheck compare(PgStatementScope scope,PgStatementRow row,PgStatementPurchase purchase,
            long approvalCount,long observedRefundAmount) {
        SettlementValues.require(scope!=null && row!=null && approvalCount>=0 && observedRefundAmount>=0,"명세 대사 입력과 관측 합계가 올바르지 않습니다.");
        String code;
        if("TEST".equals(scope.environment())) code="TEST_NOT_SETTLEABLE";
        else if(purchase==null) code="MISSING_SALE";
        else if(!"PORTONE_LIVE".equals(purchase.provider()) || !"LIVE".equals(purchase.channelType())) code="SOURCE_ENVIRONMENT_PENDING";
        else if(!scope.identityMappingVerified()) code="IDENTITY_UNVERIFIED";
        else if(!scope.portoneStoreId().equals(purchase.storeId()) || !scope.portoneChannelKey().equals(purchase.channelKey())) code="DIFFERENT_MERCHANT";
        else if(!row.orderNo().equals(purchase.paymentId())) code="DIFFERENT_ORDER";
        else if(!row.payToken().equals(purchase.transactionId())) code="DIFFERENT_TRANSACTION";
        else if(approvalCount>1) code="AMBIGUOUS_PAYMENT";
        else if("PAY".equals(row.transactionType())) code=row.amount()==purchase.amount()?"MATCHED_REPORTED":"DIFFERENT_AMOUNT";
        else if(purchase.refundAmount()==null) code="MISSING_REFUND";
        else if(!row.payToken().equals(purchase.refundTransactionId())) code="DIFFERENT_TRANSACTION";
        else if(observedRefundAmount>purchase.refundAmount()) code="EXCESS_REFUND";
        else code=observedRefundAmount==purchase.refundAmount()?"MATCHED_REPORTED":"REFUND_AMOUNT_PENDING";
        return new PgStatementCheck(code,purchase==null?null:purchase.paymentId());
    }
}
