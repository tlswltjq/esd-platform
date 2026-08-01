package com.stove.settlement.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 월 단위 판매자 정산 확정본. 세금계산서 발행 단위이기도 하다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "seller_settlement",
        uniqueConstraints = @UniqueConstraint(name = "uk_seller_settlement",
                columnNames = {"sellerId", "settlementMonth"}))
public class SellerSettlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 7)
    private String settlementMonth;

    @Column(nullable = false)
    private long grossAmount;

    @Column(nullable = false)
    private long feeAmount;

    @Column(nullable = false)
    private long netAmount;

    @Column(nullable = false)
    private int recordCount;

    @Column(length = 30)
    private String taxInvoiceNo;

    @Column(nullable = false)
    private Instant closedAt;

    private SellerSettlement(Long sellerId, String settlementMonth, long grossAmount, long feeAmount,
                             long netAmount, int recordCount, String taxInvoiceNo) {
        this.sellerId = sellerId;
        this.settlementMonth = settlementMonth;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.recordCount = recordCount;
        this.taxInvoiceNo = taxInvoiceNo;
        this.closedAt = Instant.now();
    }

    public static SellerSettlement close(Long sellerId, String settlementMonth, long grossAmount,
                                         long feeAmount, long netAmount, int recordCount, String taxInvoiceNo) {
        return new SellerSettlement(sellerId, settlementMonth, grossAmount, feeAmount,
                netAmount, recordCount, taxInvoiceNo);
    }
}
