package com.stove.order.core.domain;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결정 115: 스스로 만료된 주문에 늦게 도착한 운영자 종결 실패(REVIEW_CLOSED)의 결과 식별. 주문은 EXPIRED 그대로이며, 이 기록은
 * 같은 결과의 재전달만 무효과로 받고 바뀐 재전달·다른 주문의 같은 eventId를 충돌로 가르는 근거다.
 */
@Entity
@Table(name = "order_expired_review_closure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExpiredReviewClosure {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false, length = 36) private String orderNo;
    @Column(nullable = false, unique = true, updatable = false, length = 64) private String eventId;
    @Column(nullable = false, updatable = false, length = 64) private String paymentId;
    @Column(nullable = false, updatable = false, length = 100) private String reasonCode;
    @Column(nullable = false, updatable = false, length = 500) private String reason;
    @Column(nullable = false, updatable = false) private Instant occurredAt;
    @Column(nullable = false, updatable = false) private Instant recordedAt;

    public static ExpiredReviewClosure record(Order order, PaymentResult result, Instant at) {
        if (order == null || result == null || at == null || at.getNano() % 1000 != 0)
            throw new IllegalArgumentException("만료 주문과 운영자 종결 결과가 필요합니다.");
        if (!order.keepsExpiryFor(result)) throw new IllegalArgumentException("만료를 유지하는 운영자 종결 결과만 기록합니다.");
        ExpiredReviewClosure kept = new ExpiredReviewClosure();
        kept.orderNo = order.getOrderNo(); kept.eventId = result.eventId(); kept.paymentId = result.paymentId();
        kept.reasonCode = result.reasonCode(); kept.reason = result.reason(); kept.occurredAt = result.occurredAt(); kept.recordedAt = at;
        return kept;
    }

    /** 같은 주문의 같은 결과(eventId·결제 ID·사유·시각과 주문 연결·금액)만 무효과 재전달로 받는다. */
    public void requireSame(Order order, PaymentResult result) {
        if (order == null || result == null || !orderNo.equals(order.getOrderNo()) || !result(order).equals(result))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 기록한 운영자 종결 결과와 다릅니다.");
    }

    private PaymentResult result(Order order) {
        return new PaymentResult(eventId, occurredAt, order.getOrderNo(), order.getRequestEventId(), paymentId, order.getBuyerSubject(),
                order.getAmount(), order.getCurrency(), OrderStatus.FAILED, reasonCode, reason);
    }
}
