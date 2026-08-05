package com.stove.settlement.core.domain;

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

    /**
     * 마감 후 도착한 원장을 확정본에 더한다.
     *
     * <p>지각 원장은 예외가 아니라 상시 발생한다 — 이벤트 재전송, 월경계 지연, 수동 보정.
     * 이 경로가 없으면 그 금액이 어떤 확정본에도 속하지 못한 채 마감 처리되어 영구히 사라진다.
     *
     * <p>{@code closedAt} 은 최초 마감 시각으로 둔다. 갱신하면 "언제 마감했는가"를 잃는다.
     */
    public void accumulate(long grossAmount, long feeAmount, long netAmount, int recordCount) {
        this.grossAmount += grossAmount;
        this.feeAmount += feeAmount;
        this.netAmount += netAmount;
        this.recordCount += recordCount;
    }

    public boolean hasTaxInvoice() {
        return taxInvoiceNo != null;
    }

    /**
     * 세금계산서를 발행해야 하는 상태인가.
     *
     * <p>발행은 되돌릴 수 없는 외부 호출이라 트랜잭션 밖에서 일어난다. 그 판단을 조율 계층이
     * 필드를 조합해 내리게 두면 규칙이 도메인 밖으로 샌다 — 여기서 값으로 답한다.
     *
     * <p>환불이 매출을 초과해 순액이 0 이하가 된 판매자는 발행하지 않고 이월한다.
     */
    public boolean needsTaxInvoice() {
        return !hasTaxInvoice() && netAmount > 0;
    }

    public void assignTaxInvoice(String taxInvoiceNo) {
        this.taxInvoiceNo = taxInvoiceNo;
    }
}
