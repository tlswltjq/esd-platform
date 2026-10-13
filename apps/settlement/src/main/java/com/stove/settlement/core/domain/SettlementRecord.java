package com.stove.settlement.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import com.stove.common.kafka.contract.payload.OrderLine;
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

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문·결제·이용권·정산을 연결하는 주문번호. */
    @Column(nullable = false, length = 40)
    private String orderNo;

    /** Catalog 판매 상품 식별자. */
    @Column(nullable = false)
    private Long productId;

    /** 판매자를 나타내는 워크스페이스 식별자. */
    @Column(nullable = false)
    private Long sellerId;

    /** 자체 판매와 입점 판매를 구분하는 정산 유형. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SaleType saleType;

    /** 매출 원장과 환불 원장을 구분하는 유형. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecordType recordType;

    /** 판매가 총액(환불은 음수) */
    @Column(nullable = false)
    private long grossAmount;

    /** 고객 실결제액과 할인 부담의 불변 사본. REFUND에서는 모두 음수다. */
    @Column(nullable = false)
    private long paidAmount;

    /** 할인 전 정가에 수량을 곱한 금액. */
    @Column(nullable = false)
    private long listAmount;

    /** 할인으로 차감한 금액. */
    @Column(nullable = false)
    private long discountAmount;

    /** 플랫폼이 부담한 할인 비용. */
    @Column(nullable = false)
    private long platformExpense;

    /** 구매 가격에 적용한 할인 행사 식별자. */
    private Long promotionId;

    /** 할인 비용 부담 주체(SELLER 또는 PLATFORM). */
    @Column(length = 20)
    private String discountBearer;

    /** 마감 후 환불로 조정할 원매출의 귀속 월. */
    @Column(length = 7)
    private String adjustmentForMonth;

    /** 정산 기준액에 적용할 수수료율. */
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

    /** 이 원장이 월 마감 합계에 반영되었는지 여부. */
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
        this.paidAmount = grossAmount;
        this.listAmount = grossAmount;
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

    public static SettlementRecord sale(String orderNo, OrderLine line, SaleType saleType,
                                        BigDecimal feeRate, YearMonth month) {
        boolean platform = "PLATFORM".equals(line.discountBearer());
        long basis = line.settlementBasis() == null
                ? (platform ? line.listAmount() : line.lineAmount()) : line.settlementBasis();
        SettlementRecord record = sale(orderNo, line.productId(), line.sellerId(), saleType,
                basis, line.feeRate() == null ? feeRate : line.feeRate(), month);
        if (line.feeAmount() != null) {
            record.feeAmount = line.feeAmount();
            record.netAmount = line.sellerPayout();
        }
        record.paidAmount = line.lineAmount();
        record.listAmount = line.listAmount();
        record.discountAmount = line.discountAmount();
        record.platformExpense = platform ? line.discountAmount() : 0;
        record.promotionId = line.promotionId();
        record.discountBearer = line.discountBearer();
        return record;
    }

    /** 환불 역산: 원 매출 레코드를 부호만 뒤집어 상계 처리한다. */
    public static SettlementRecord refundOf(SettlementRecord sale, YearMonth month) {
        SettlementRecord record = new SettlementRecord(sale.orderNo, sale.productId, sale.sellerId, sale.saleType,
                RecordType.REFUND, -sale.grossAmount, sale.feeRate, month.toString());
        record.paidAmount = -sale.paidAmount;
        record.listAmount = -sale.listAmount;
        record.discountAmount = -sale.discountAmount;
        record.platformExpense = -sale.platformExpense;
        record.promotionId = sale.promotionId;
        record.discountBearer = sale.discountBearer;
        record.adjustmentForMonth = sale.closed ? sale.settlementMonth : null;
        return record;
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
