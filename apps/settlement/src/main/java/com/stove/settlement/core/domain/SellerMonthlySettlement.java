package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="seller_monthly_settlement") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SellerMonthlySettlement {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=100,updatable=false) private String sellerSubject;
    @Column(nullable=false,length=7,updatable=false) private String settlementMonth;
    @Column(nullable=false,length=10) private String status;
    private long paidAmount;
    private long platformFeeAmount;
    private long sellerPayoutAmount;
    private long pgFeeAmount;
    private long platformNetAmount;
    private long recordCount;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    private Instant closedAt;

    public static SellerMonthlySettlement open(String seller,String month,Instant now) {
        SettlementValues.require(SettlementValues.text(seller,100) && SettlementValues.microsecond(now),"월 정산 입력이 올바르지 않습니다.");
        new SettlementMonth(month);
        SellerMonthlySettlement result=new SellerMonthlySettlement();
        result.sellerSubject=seller; result.settlementMonth=month; result.status="OPEN";
        result.createdAt=now; result.updatedAt=now; return result;
    }
    public SettlementTotals totals() {
        return new SettlementTotals(paidAmount,platformFeeAmount,sellerPayoutAmount,pgFeeAmount,platformNetAmount,recordCount);
    }
    public boolean isClosed() { return "CLOSED".equals(status); }
    public void add(SettlementTotals sale,Instant now) {
        SettlementValues.consistent(!isClosed(),"마감한 월에는 매출을 반영할 수 없습니다.");
        SettlementTotals sum=totals().plus(sale);
        paidAmount=sum.paidAmount(); platformFeeAmount=sum.platformFeeAmount(); sellerPayoutAmount=sum.sellerPayoutAmount();
        pgFeeAmount=sum.pgFeeAmount(); platformNetAmount=sum.platformNetAmount(); recordCount=sum.recordCount(); updatedAt=now;
    }
    public void close(SettlementTotals actual,Instant now) {
        if(isClosed()) return;
        SettlementValues.consistent(totals().equals(actual),"원장과 월 합계가 다릅니다.");
        status="CLOSED"; closedAt=now; updatedAt=now;
    }
}
