package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="settlement_sale") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SettlementSale {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false,length=64) private String eventId;
    @Column(nullable=false,updatable=false) private Instant occurredAt;
    @Column(nullable=false,updatable=false,length=36) private String orderNo;
    @Column(nullable=false,updatable=false,length=64) private String requestEventId;
    @Column(nullable=false,updatable=false,length=64) private String paymentResultEventId;
    @Column(nullable=false,updatable=false,length=64) private String paymentId;
    @Column(nullable=false,updatable=false,length=100) private String buyerSubject;
    @Column(nullable=false,updatable=false) private Long productId;
    @Column(nullable=false,updatable=false,length=50) private String productCode;
    @Column(nullable=false,updatable=false,length=200) private String title;
    @Column(nullable=false,updatable=false) private long contentVersion;
    @Column(nullable=false,updatable=false) private long amount;
    @Column(nullable=false,updatable=false,length=3) private String currency;
    @Column(nullable=false,length=100,updatable=false) private String sellerSubject;
    @Column(nullable=false,length=100,updatable=false) private String platformPolicyId;
    @Column(nullable=false,updatable=false) private int platformFeeRateBps;
    @Column(nullable=false,updatable=false) private long platformFeeAmount;
    @Column(nullable=false,updatable=false) private long sellerPayoutAmount;
    @Column(nullable=false,updatable=false) private long platformNetAmount;
    @Embedded private PgCost pgCost;
    @Column(nullable=false,length=7,updatable=false) private String saleMonth;
    @Column(nullable=false,length=7,updatable=false) private String settlementMonth;
    @Column(nullable=false,updatable=false) private Instant receivedAt;

    public static SettlementSale record(PaidOrder order,ProductOwner owner,PgPayment payment,
            PlatformFeePolicy policy,String month,Instant receivedAt) {
        owner.requireOrder(order); payment.requireOrder(order);
        new SettlementMonth(month);
        SettlementValues.require(SettlementValues.microsecond(receivedAt),"접수 시각이 올바르지 않습니다.");
        SettlementSale sale=new SettlementSale();
        sale.eventId=order.eventId();
        sale.occurredAt=order.occurredAt();
        sale.orderNo=order.orderNo();
        sale.requestEventId=order.requestEventId();
        sale.paymentResultEventId=order.paymentResultEventId();
        sale.paymentId=order.paymentId();
        sale.buyerSubject=order.buyerSubject();
        sale.productId=order.productId();
        sale.productCode=order.productCode();
        sale.title=order.title();
        sale.contentVersion=order.contentVersion();
        sale.amount=order.amount();
        sale.currency=order.currency();
        sale.sellerSubject=owner.sellerSubject(); sale.platformPolicyId=policy.policyId(); sale.platformFeeRateBps=policy.rateBps();
        sale.platformFeeAmount=policy.fee(order.amount()); sale.sellerPayoutAmount=Math.subtractExact(order.amount(),sale.platformFeeAmount);
        sale.pgCost=payment.cost(); sale.platformNetAmount=Math.subtractExact(sale.platformFeeAmount,payment.cost().getPgFeeAmount());
        sale.saleMonth=SettlementMonth.at(order.occurredAt()).value(); sale.settlementMonth=month; sale.receivedAt=receivedAt;
        sale.totals(); return sale;
    }

    public PaidOrder source() { return new PaidOrder(eventId,occurredAt,orderNo,requestEventId,paymentResultEventId,paymentId,buyerSubject,productId,productCode,title,contentVersion,amount,currency); }
    public PgPayment pgPayment() { return new PgPayment(paymentId,orderNo,amount,currency,pgCost); }
    public void requireSame(PaidOrder input) {
        SettlementValues.consistent(source().equals(input),"같은 주문의 매출 입력이 다릅니다.");
    }
    public SettlementTotals totals() {
        return new SettlementTotals(amount,platformFeeAmount,sellerPayoutAmount,pgCost.getPgFeeAmount(),platformNetAmount,1);
    }
}
