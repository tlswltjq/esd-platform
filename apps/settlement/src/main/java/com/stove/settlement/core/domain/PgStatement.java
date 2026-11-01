package com.stove.settlement.core.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="settlement_pg_statement") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PgStatement {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false,length=100) private String merchantId;
    @Column(nullable=false,updatable=false,length=10) private String environment;
    @Column(nullable=false,updatable=false,length=100) private String portoneStoreId;
    @Column(nullable=false,updatable=false,length=100) private String portoneChannelKey;
    @Column(nullable=false,updatable=false) private boolean identityMappingVerified;
    @Column(nullable=false,updatable=false,length=10) private String dateType;
    @Column(nullable=false,updatable=false) private LocalDate baseDate;
    @Column(nullable=false,updatable=false,length=64) private String fingerprint;
    @Column(nullable=false,updatable=false) private long totalAmount;
    @Column(nullable=false,updatable=false) private long totalFee;
    @Column(nullable=false,updatable=false) private long totalVat;
    @Column(nullable=false,updatable=false) private long totalFeeVatSum;
    @Column(nullable=false,updatable=false) private long recordCount;
    @Column(nullable=false,updatable=false) private int pageCount;
    @Column(nullable=false,updatable=false) private Instant receivedAt;
    @Column(nullable=false,updatable=false,length=100) private String collectedBy;
    private Instant checkedAt;
    @Column(nullable=false,length=30) private String status;
    @ElementCollection @CollectionTable(name="settlement_pg_statement_page",joinColumns=@JoinColumn(name="statement_id"))
    @OrderColumn(name="page_position") private List<PgStatementPage> pages=new ArrayList<>();

    public static PgStatement record(PgStatementReport report,String operator,Instant now) {
        SettlementValues.require(report!=null && SettlementValues.text(operator,100) && SettlementValues.microsecond(now),"명세 수집 주체와 시각이 올바르지 않습니다.");
        PgStatement result=new PgStatement();PgStatementScope scope=report.scope();
        result.merchantId=scope.merchantId();result.environment=scope.environment();result.portoneStoreId=scope.portoneStoreId();
        result.portoneChannelKey=scope.portoneChannelKey();result.identityMappingVerified=scope.identityMappingVerified();
        result.dateType=report.query().dateType();result.baseDate=report.query().baseDate();result.fingerprint=fingerprint(report);
        result.totalAmount=report.totals().amount();result.totalFee=report.totals().fee();result.totalVat=report.totals().vat();
        result.totalFeeVatSum=report.totals().feeVatSum();result.recordCount=report.rows().size();result.pageCount=report.pages().size();
        result.receivedAt=now;result.collectedBy=operator;result.pages.addAll(report.pages());
        result.status=report.rows().isEmpty() ? ("TEST".equals(scope.environment())?"EMPTY_TEST":"EMPTY") : "RECEIVED";
        return result;
    }
    public PgStatementScope scope() { return new PgStatementScope(merchantId,environment,portoneStoreId,portoneChannelKey,identityMappingVerified); }
    public PgStatementTotals totals() { return new PgStatementTotals(totalAmount,totalFee,totalVat,totalFeeVatSum); }
    public List<PgStatementPage> getPages() { return List.copyOf(pages); }
    public void finishCheck(long matches,long pending,long mismatches,Instant now) {
        SettlementValues.require(matches>=0 && pending>=0 && mismatches>=0 && Math.addExact(Math.addExact(matches,pending),mismatches)==recordCount
                && SettlementValues.microsecond(now),"명세 대사 건수와 시각이 올바르지 않습니다.");
        if(recordCount>0) status=mismatches>0?"MISMATCH":pending>0?"PENDING":"MATCHED_REPORTED";
        checkedAt=now;
    }
    public static String fingerprint(PgStatementReport report) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String value:List.of(report.scope().merchantId(),report.scope().environment(),report.scope().portoneStoreId(),
                    report.scope().portoneChannelKey(),Boolean.toString(report.scope().identityMappingVerified()),report.query().dateType(),report.query().baseDate().toString())) update(digest,value);
            for(PgStatementPage page:report.pages()) {update(digest,page.getRequestCursor()==null?"":page.getRequestCursor());update(digest,page.getNextCursor());update(digest,page.getResponseBody());}
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void update(MessageDigest digest,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update((bytes.length+":").getBytes(StandardCharsets.UTF_8));digest.update(bytes);
    }
}
