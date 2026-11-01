package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A transaction occurs once even when observed through DUE and SETTLE queries. */
@Entity @Table(name="settlement_pg_statement_entry") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgStatementEntry {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false,length=100) private String merchantId;
    @Column(nullable=false,updatable=false,length=10) private String environment;
    @Column(nullable=false,updatable=false,length=10) private String transactionType;
    @Column(nullable=false,updatable=false,length=100) private String transactionId;
    @Column(nullable=false,updatable=false,length=100) private String payToken;
    @Column(nullable=false,updatable=false,length=100) private String orderNo;
    @Column(updatable=false,length=2000) private String productDesc;
    @Column(nullable=false,updatable=false) private long amount;
    @Column(nullable=false,updatable=false) private int fee;
    @Column(nullable=false,updatable=false) private int vat;
    @Column(nullable=false,updatable=false) private int feeVatSum;
    @Column(nullable=false,updatable=false,length=20) private String payMethod;
    @Column(nullable=false,updatable=false) private LocalDate dueDate;
    @Column(nullable=false,updatable=false) private Instant receivedAt;
    public static PgStatementEntry record(PgStatementScope scope,PgStatementRow row,Instant now) {
        SettlementValues.require(scope!=null && row!=null && SettlementValues.microsecond(now),"정산 거래 근거가 필요합니다.");
        PgStatementEntry result=new PgStatementEntry();result.merchantId=scope.merchantId();result.environment=scope.environment();
        result.transactionType=row.transactionType();result.transactionId=row.transactionId();result.payToken=row.payToken();result.orderNo=row.orderNo();
        result.productDesc=row.productDesc();result.amount=row.amount();result.fee=row.fee();result.vat=row.vat();result.feeVatSum=row.feeVatSum();
        result.payMethod=row.payMethod();result.dueDate=row.dueDate();result.receivedAt=now;return result;
    }
    public PgStatementRow value() {return new PgStatementRow(transactionType,transactionId,payToken,orderNo,productDesc,amount,fee,vat,feeVatSum,payMethod,dueDate);}
    public void requireSame(PgStatementRow row) { SettlementValues.consistent(value().equals(row),"같은 PG 거래 ID의 정산 명세가 변경됐습니다."); }
}
