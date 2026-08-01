package com.stove.order.domain;

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
    private int quantity;

    OrderItem(Order order, Long productId, String productName, Long sellerId, long unitPrice, int quantity) {
        this.order = order;
        this.productId = productId;
        this.productName = productName;
        this.sellerId = sellerId;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public long lineAmount() {
        return unitPrice * quantity;
    }
}
