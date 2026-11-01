package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="pg_receipt_item") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgReceiptItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="receipt_id",updatable=false) private PgReceipt receipt;
    @Column(nullable=false,updatable=false) private int itemPosition;
    @Column(nullable=false,length=64,updatable=false) private String paymentId;
    @Column(nullable=false,length=36,updatable=false) private String orderNo;
    @Column(nullable=false,updatable=false) private long amount;
    @Column(nullable=false,length=3,updatable=false) private String currency;
    @Embedded private PgCost cost;
    @Column(length=100) private String mismatchCode;
    @Column(length=500) private String mismatchReason;

    static PgReceiptItem from(PgReceipt receipt,int position,PgPayment payment) {
        PgReceiptItem item=new PgReceiptItem(); item.receipt=receipt; item.itemPosition=position;
        item.paymentId=payment.paymentId(); item.orderNo=payment.orderNo(); item.amount=payment.amount();
        item.currency=payment.currency(); item.cost=payment.cost(); return item;
    }
    public PgPayment payment() { return new PgPayment(paymentId,orderNo,amount,currency,cost); }
    public void compare(SettlementSale sale,PgTransactionRecord transaction,PgFeeRecord fee) {
        if(sale==null) mismatchCode="MISSING_SALE";
        else if(transaction==null || !transaction.payment(null).equals(new PgPayment(paymentId,orderNo,amount,currency,
                new PgEvidence(cost.getProvider(),cost.getProviderTransactionId(),cost.getProviderOccurredAt()),null))
                || !sale.getPaymentId().equals(paymentId) || !sale.getProvider().equals(cost.getProvider())
                || !sale.getProviderTransactionId().equals(cost.getProviderTransactionId())) mismatchCode="DIFFERENT_TRANSACTION";
        else if(fee==null) mismatchCode="PG_COST_PENDING";
        else mismatchCode=fee.getCost().equals(cost) ? null : "DIFFERENT_COST";
        mismatchReason=mismatchCode==null ? null : "MISSING_SALE".equals(mismatchCode) ? "내부 매출이 아직 도착하지 않았습니다." : "PG_COST_PENDING".equals(mismatchCode) ? "PG 비용 확인을 기다리고 있습니다." : "내부 거래 또는 비용 근거가 다릅니다.";
    }
}
