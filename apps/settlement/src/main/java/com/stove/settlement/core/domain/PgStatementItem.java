package com.stove.settlement.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="settlement_pg_statement_item") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgStatementItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false) private Long statementId;
    @Column(nullable=false,updatable=false) private int itemPosition;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="entry_id",updatable=false) private PgStatementEntry entry;
    @Column(nullable=false,length=40) private String checkCode;
    @Column(length=64) private String paymentId;
    private Instant checkedAt;
    public static PgStatementItem record(Long statementId,int position,PgStatementEntry entry) {
        SettlementValues.require(statementId!=null && position>=0 && entry!=null,"명세 항목의 연결이 올바르지 않습니다.");
        PgStatementItem result=new PgStatementItem();result.statementId=statementId;result.itemPosition=position;result.entry=entry;result.checkCode="UNCHECKED";return result;
    }
    public void check(PgStatementCheck check,Instant now) {
        SettlementValues.require(check!=null && SettlementValues.microsecond(now),"명세 대사 결과가 필요합니다.");
        checkCode=check.code();paymentId=check.paymentId();checkedAt=now;
    }
}
