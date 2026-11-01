package com.stove.settlement.core.domain;

/** Read snapshot of a recorded purchase; creating this value cannot create an approval. */
public record PgStatementPurchase(String paymentId,String provider,String transactionId,String storeId,String channelKey,
        String channelType,long amount,Long refundAmount,String refundTransactionId) {
    public PgStatementPurchase {
        SettlementValues.require(SettlementValues.text(paymentId,64) && SettlementValues.text(provider,30)
                && SettlementValues.text(transactionId,100) && amount>0 && (refundAmount==null || refundAmount>0 && refundAmount<=amount)
                && (refundAmount==null ? refundTransactionId==null : SettlementValues.text(refundTransactionId,100)),
                "대사할 원구매 스냅샷이 올바르지 않습니다.");
    }
}
