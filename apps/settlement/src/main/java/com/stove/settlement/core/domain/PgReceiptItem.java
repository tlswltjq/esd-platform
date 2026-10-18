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
    public void compare(SettlementSale sale) {
        mismatchCode=sale==null ? "MISSING_SALE" : sale.pgPayment().equals(payment()) ? null : "DIFFERENT_TRANSACTION";
        mismatchReason=mismatchCode==null ? null : "MISSING_SALE".equals(mismatchCode) ? "내부 매출이 아직 도착하지 않았습니다." : "내부 매출과 PG 거래 근거가 다릅니다.";
    }
}
