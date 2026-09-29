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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false, length = 200)
    private String productName;

    /** 정산 배분 기준. 주문 시점의 판매자를 고정한다(이후 판매자 변경과 무관하게 정산은 불변). */
    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private long listUnitPrice;

    @Column(nullable = false)
    private long discountPerUnit;

    private Long promotionId;

    @Column(length = 20)
    private String discountBearer;

    private Long settlementBasis;

    @Column(precision = 5, scale = 4)
    private BigDecimal feeRate;

    private Long feeAmount;

    private Long sellerPayout;

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
