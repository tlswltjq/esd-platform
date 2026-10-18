package com.stove.settlement.core.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="pg_receipt") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgReceipt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=30,updatable=false) private String provider;
    @Column(nullable=false,length=64,updatable=false) private String providerSettlementId;
    @Column(nullable=false,length=3,updatable=false) private String currency;
    @Column(nullable=false,updatable=false) private Instant cutoffAt;
    @Column(nullable=false,updatable=false) private Instant paidAt;
    @Column(nullable=false,length=30,updatable=false) private String providerStatus;
    @Column(nullable=false,updatable=false) private long paymentAmountTotal;
    @Column(nullable=false,updatable=false) private long pgFeeAmountTotal;
    @Column(nullable=false,updatable=false) private long payoutAmount;
    @Column(nullable=false,updatable=false) private long recordCount;
    @Column(nullable=false,length=30) private String status;
    @Column(nullable=false,length=100,updatable=false) private String reportedBy;
    @Column(nullable=false,updatable=false) private Instant reportedAt;
    private Instant lastCheckedAt;
    private Instant simulatedReceivedAt;
    @OneToMany(mappedBy="receipt",cascade=CascadeType.ALL) @OrderBy("itemPosition ASC")
    private List<PgReceiptItem> items=new ArrayList<PgReceiptItem>();

    public static PgReceipt receive(PgSettlement report,String operator,Instant now) {
        SettlementValues.require(SettlementValues.text(operator,100) && SettlementValues.microsecond(now),"명세 접수 주체가 올바르지 않습니다.");
        PgReceipt receipt=new PgReceipt(); receipt.provider=report.provider(); receipt.providerSettlementId=report.providerSettlementId();
        receipt.currency=report.currency(); receipt.cutoffAt=report.cutoffAt(); receipt.paidAt=report.paidAt(); receipt.providerStatus=report.status();
        receipt.paymentAmountTotal=report.paymentAmountTotal(); receipt.pgFeeAmountTotal=report.pgFeeAmountTotal();
        receipt.payoutAmount=report.payoutAmount(); receipt.recordCount=report.recordCount(); receipt.reportedBy=operator;
        receipt.reportedAt=now; receipt.status="REPORTED_PAID";
        for(int i=0;i<report.items().size();i++) receipt.items.add(PgReceiptItem.from(receipt,i,report.items().get(i)));
        return receipt;
    }
    public PgSettlement statement() {
        return new PgSettlement(provider,providerSettlementId,currency,cutoffAt,paidAt,providerStatus,
                paymentAmountTotal,pgFeeAmountTotal,payoutAmount,recordCount,items.stream().map(PgReceiptItem::payment).toList());
    }
    public void requireSame(PgSettlement report) {
        SettlementValues.consistent(statement().equals(report),"같은 PG 정산번호의 명세가 다릅니다.");
    }
    public boolean isReceived() { return "SIMULATED_RECEIVED".equals(status); }
    public void finishCheck(Instant now) {
        if(isReceived()) return;
        boolean missing=items.stream().anyMatch(i -> "MISSING_SALE".equals(i.getMismatchCode()));
        boolean different=items.stream().anyMatch(i -> "DIFFERENT_TRANSACTION".equals(i.getMismatchCode()));
        status=different ? "MISMATCH" : missing ? "REPORTED_PAID" : "SIMULATED_RECEIVED";
        lastCheckedAt=now;
        if(isReceived()) simulatedReceivedAt=now;
    }
    public PgReceiptView view() {
        return new PgReceiptView(statement(),status,reportedBy,reportedAt,lastCheckedAt,simulatedReceivedAt,
                items.stream().map(i -> new PgReceiptView.ItemCheck(i.getPaymentId(),i.getMismatchCode(),i.getMismatchReason())).toList());
    }
}
