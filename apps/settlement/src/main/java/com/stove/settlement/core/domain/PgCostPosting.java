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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable link from one provider transaction to the original business posting month. */
@Entity @Table(name="settlement_pg_cost_posting") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgCostPosting {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="source_entry_id",updatable=false) private PgStatementEntry entry;
    @Column(nullable=false,updatable=false) private Long saleId;
    @Column(updatable=false) private Long refundRecordId;
    @Column(nullable=false,updatable=false,length=64) private String paymentId;
    @Column(nullable=false,updatable=false,length=100) private String sellerSubject;
    @Column(nullable=false,updatable=false,length=7) private String businessMonth;
    @Column(nullable=false,updatable=false,length=100) private String policyId;
    @Column(nullable=false,updatable=false) private Long verificationStatementId;
    @Column(nullable=false,updatable=false) private Instant verifiedAt;

    @Column(nullable=false,length=40) private String checkCode;

    public static PgCostPosting record(PgStatementEntry entry,SettlementSale sale,SettlementRefund refund,Long statementId,Instant now) {
        SettlementValues.require(entry!=null && sale!=null && sale.getId()!=null && statementId!=null && statementId>0
                && SettlementValues.microsecond(now),"PG 비용 연결의 원본과 검증 근거가 필요합니다.");
        SettlementValues.consistent("LIVE".equals(entry.getEnvironment()) && "PORTONE_LIVE".equals(sale.getProvider())
                && entry.getOrderNo().equals(sale.getPaymentId()) && entry.getPayToken().equals(sale.getProviderTransactionId()),
                "PG 비용과 원매출의 환경·거래가 다릅니다.");
        boolean pay="PAY".equals(entry.getTransactionType());
        SettlementValues.consistent(pay ? refund==null && entry.getAmount()==sale.getAmount()
                : refund!=null && refund.getId()!=null && sale.getOrderNo().equals(refund.getOrderNo())
                    && sale.getPaymentId().equals(refund.getPaymentId()) && sale.getSellerSubject().equals(refund.getSellerSubject())
                    && entry.getPayToken().equals(refund.getProviderTransactionId()),"PG 비용과 매출·환불 연결이 다릅니다.");
        PgCostPosting result=new PgCostPosting();result.entry=entry;result.saleId=sale.getId();result.refundRecordId=pay?null:refund.getId();
        result.paymentId=sale.getPaymentId();result.sellerSubject=sale.getSellerSubject();
        result.businessMonth=new SettlementMonth(pay?sale.getSettlementMonth():refund.getSettlementMonth()).value();
        result.checkCode="MATCHED_REPORTED";result.policyId="LINKED_BUSINESS_MONTH_V1";result.verificationStatementId=statementId;result.verifiedAt=now;return result;
    }
    public void check(PgStatementCheck check) {
        SettlementValues.require(check!=null,"PG 비용의 현재 대사 근거가 필요합니다.");
        // An unverified observation cannot downgrade a link established under a verified scope.
        if(check.matched() || "AMBIGUOUS_PAYMENT".equals(check.code()) || "EXCESS_REFUND".equals(check.code())) checkCode=check.code();
    }
    public void requireSame(PgCostPosting candidate) {
        SettlementValues.consistent(saleId.equals(candidate.saleId) && Objects.equals(refundRecordId,candidate.refundRecordId)
                && paymentId.equals(candidate.paymentId) && sellerSubject.equals(candidate.sellerSubject)
                && businessMonth.equals(candidate.businessMonth) && policyId.equals(candidate.policyId),"이미 저장한 PG 비용 연결과 다릅니다.");
    }
}
