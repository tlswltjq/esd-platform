package com.stove.settlement.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정산 원장 한 줄. 주문 항목 단위로 매출/환불을 기록한다.
 *
 * <p>(order_no, product_id, record_type) 유니크로 결제·환불 이벤트가 재전송돼도
 * 매출이 중복 집계되지 않는다 — 정산에서 멱등성이 깨지면 곧바로 금전 사고가 된다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_record",
                columnNames = {"orderNo", "productId", "recordType"}))
public class SettlementRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String orderNo;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SaleType saleType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecordType recordType;

    /** 판매가 총액(환불은 음수) */
    @Column(nullable = false)
    private long grossAmount;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    /** 중개 수수료(환불은 음수) */
    @Column(nullable = false)
    private long feeAmount;

    /** 판매자 정산액 = gross - fee */
    @Column(nullable = false)
    private long netAmount;

    /** 정산 귀속 월(yyyy-MM) */
    @Column(nullable = false, length = 7)
    private String settlementMonth;

    @Column(nullable = false)
    private boolean closed;

    private SettlementRecord(String orderNo, Long productId, Long sellerId, SaleType saleType,
                             RecordType recordType, long grossAmount, BigDecimal feeRate, String settlementMonth) {
        this.orderNo = orderNo;
        this.productId = productId;
        this.sellerId = sellerId;
        this.saleType = saleType;
        this.recordType = recordType;
        this.grossAmount = grossAmount;
        this.feeRate = feeRate;
        this.feeAmount = calculateFee(grossAmount, feeRate);
        this.netAmount = grossAmount - this.feeAmount;
        this.settlementMonth = settlementMonth;
        this.closed = false;
    }

    public static SettlementRecord sale(String orderNo, Long productId, Long sellerId, SaleType saleType,
                                        long grossAmount, BigDecimal feeRate, YearMonth month) {
        return new SettlementRecord(orderNo, productId, sellerId, saleType, RecordType.SALE,
                grossAmount, feeRate, month.toString());
    }

    /** 환불 역산: 원 매출 레코드를 부호만 뒤집어 상계 처리한다. */
    public static SettlementRecord refundOf(SettlementRecord sale, YearMonth month) {
        return new SettlementRecord(sale.orderNo, sale.productId, sale.sellerId, sale.saleType,
                RecordType.REFUND, -sale.grossAmount, sale.feeRate, month.toString());
    }

    public void close() {
        this.closed = true;
    }

    private static long calculateFee(long grossAmount, BigDecimal feeRate) {
        return BigDecimal.valueOf(grossAmount)
                .multiply(feeRate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
