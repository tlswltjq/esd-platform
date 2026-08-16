package com.stove.order.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 애그리거트.
 * 금액은 항상 항목 합계로 계산되며, 클라이언트가 보낸 금액을 신뢰하지 않는다.
 */
@Entity
@Getter
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 서비스 간 상관관계 키. 결제·라이선스·정산이 이 값으로 멱등성을 보장한다. */
    @Column(nullable = false, unique = true, length = 40)
    private String orderNo;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private long totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    private Instant paidAt;

    private Instant canceledAt;

    @Column(length = 200)
    private String cancelReason;

    private Instant failedAt;

    @Column(length = 200)
    private String failReason;

    /**
     * 만료 처리된 시각.
     *
     * <p>{@code createdAt} 으로 대신할 수 없다 — 그건 <b>만료 대상이 된 시각</b>이지
     * <b>실제로 만료시킨 시각</b>이 아니다. 밀린 주문을 나눠서 처리하므로 둘이 크게 벌어질 수 있고,
     * 그 차이가 곧 "스윕이 얼마나 밀렸나" 다.
     */
    private Instant expiredAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<OrderItem> items = new ArrayList<>();

    private Order(String orderNo, Long memberId, String currency) {
        this.orderNo = orderNo;
        this.memberId = memberId;
        this.currency = currency;
        this.status = OrderStatus.CREATED;
        this.totalAmount = 0L;
    }

    public static Order create(String orderNo, Long memberId, String currency, List<OrderLine> lines) {
        Order order = new Order(orderNo, memberId, currency);
        lines.forEach(order::addLine);
        return order;
    }

    private void addLine(OrderLine line) {
        items.add(new OrderItem(this, line.productId(), line.productName(), line.sellerId(),
                line.unitPrice(), line.quantity()));
        this.totalAmount += line.lineAmount();
    }

    public List<OrderLine> toOrderLines() {
        return items.stream()
                .map(i -> new OrderLine(i.getProductId(), i.getProductName(), i.getSellerId(),
                        i.getUnitPrice(), i.getQuantity()))
                .toList();
    }

    public void markPaid() {
        if (status == OrderStatus.PAID) {
            return; // 이벤트 중복 수신 방어
        }
        if (status != OrderStatus.CREATED) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 확정할 수 없는 주문 상태: " + status);
        }
        this.status = OrderStatus.PAID;
        this.paidAt = Instant.now();
    }

    /**
     * PG 승인 거절로 주문을 종료한다.
     *
     * <p>취소와 다르다 — 취소는 승인된 결제를 되돌리는 것이라 {@code PAID} 에서도 열려 있지만,
     * 실패는 승인 자체가 없었던 경우라 {@code CREATED} 에서만 들어온다.
     */
    public void markFailed(String reason) {
        if (status == OrderStatus.FAILED) {
            return; // 이벤트 중복 수신 방어
        }
        if (status != OrderStatus.CREATED) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 실패 처리할 수 없는 주문 상태: " + status);
        }
        this.status = OrderStatus.FAILED;
        this.failedAt = Instant.now();
        this.failReason = reason;
    }

    public void cancel(String reason) {
        if (status == OrderStatus.CANCELED) {
            return;
        }
        if (status != OrderStatus.CREATED && status != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELABLE, "취소 불가 상태: " + status);
        }
        this.status = OrderStatus.CANCELED;
        this.canceledAt = Instant.now();
        this.cancelReason = reason;
    }

    /**
     * 결제 창이 지나 만료시킨다.
     *
     * <p><b>{@code CREATED} 에서만 열린다.</b> 결제가 한 걸음이라도 진행된 주문은 결과 이벤트가
     * 상태를 바꾸므로 시간이 끼어들 자리가 아니다 — 만약 그런 주문이 여기 들어온다면
     * 스윕의 조회 조건이 틀린 것이고, <b>조용히 넘기면 그 사실이 묻힌다.</b>
     *
     * <p>{@code cancel} 과 나눠 쓴다. 사유를 남기지 않는 이유는 사유가 하나뿐이기 때문이다 —
     * 상태 자체가 사유다.
     */
    public void expire() {
        if (status == OrderStatus.EXPIRED) {
            return;
        }
        if (status != OrderStatus.CREATED) {
            throw new BusinessException(ErrorCode.CONFLICT, "만료할 수 없는 주문 상태: " + status);
        }
        this.status = OrderStatus.EXPIRED;
        this.expiredAt = Instant.now();
    }

    public void requireOwner(Long memberId) {
        if (!this.memberId.equals(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
