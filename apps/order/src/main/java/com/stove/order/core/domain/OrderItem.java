package com.stove.order.core.domain;

import com.stove.common.event.payload.OrderLine;
import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문 시점의 가격 스냅샷. 이후 상품 가격이 바뀌어도 주문/정산 금액은 불변이다. */
@Entity
@Getter
@Table(name = "order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    /** 저장된 대상을 구별하는 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 항목이 속한 주문. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Catalog 판매 상품 식별자. */
    @Column(nullable = false)
    private Long productId;

    /** 주문 시점에 고정한 상품명. */
    @Column(nullable = false, length = 200)
    private String productName;

    /** 정산 배분 기준. 주문 시점의 판매자를 고정한다(이후 판매자 변경과 무관하게 정산은 불변). */
    @Column(nullable = false)
    private Long sellerId;

    /** 할인을 반영한 상품 한 개의 실결제 단가. */
    @Column(nullable = false)
    private long unitPrice;

    /** 할인 전 상품 한 개의 정가. */
    @Column(nullable = false)
    private long listUnitPrice;

    /** 상품 한 개에 적용하는 할인액. */
    @Column(nullable = false)
    private long discountPerUnit;

    /** 구매 가격에 적용한 할인 행사 식별자. */
    private Long promotionId;

    /** 할인 비용 부담 주체(SELLER 또는 PLATFORM). */
    @Column(length = 20)
    private String discountBearer;

    /** 수수료와 판매자 몫을 계산하는 정산 기준액. */
    private Long settlementBasis;

    /** 정산 기준액에 적용할 수수료율. */
    @Column(precision = 5, scale = 4)
    private BigDecimal feeRate;

    /** 정산 기준액에서 차감할 수수료. */
    private Long feeAmount;

    /** 수수료 차감 후 판매자에게 배분할 금액. */
    private Long sellerPayout;

    /** 구매할 상품 수량. */
    @Column(nullable = false)
    private int quantity;

    OrderItem(Order order, OrderLine line) {
        this.order = order;
        this.productId = line.productId();
        this.productName = line.productName();
        this.sellerId = line.sellerId();
        this.unitPrice = line.unitPrice();
        this.quantity = line.quantity();
        this.listUnitPrice = line.listUnitPrice();
        this.discountPerUnit = line.discountPerUnit();
        this.promotionId = line.promotionId();
        this.discountBearer = line.discountBearer();
        this.settlementBasis = line.settlementBasis();
        this.feeRate = line.feeRate();
        this.feeAmount = line.feeAmount();
        this.sellerPayout = line.sellerPayout();
    }

    public OrderLine toOrderLine() {
        return new OrderLine(productId, productName, sellerId, unitPrice, quantity,
                listUnitPrice, discountPerUnit, promotionId, discountBearer,
                settlementBasis, feeRate, feeAmount, sellerPayout);
    }

    public long lineAmount() {
        return unitPrice * quantity;
    }
}
