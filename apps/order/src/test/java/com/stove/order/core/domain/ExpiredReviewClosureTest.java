package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static com.stove.order.OrderFixtures.result;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 결정 115: 만료를 유지한 운영자 종결 실패의 결과 식별은 같은 주문·같은 결과의 재전달만 받아들인다. */
class ExpiredReviewClosureTest {
    @Test void onlyAReviewClosureOfAnExpiredOrderCanBeKept() {
        Order pending = order();
        PaymentResult closed = failure(pending, "REVIEW_CLOSED");
        assertThatThrownBy(() -> ExpiredReviewClosure.record(pending, closed, pending.getExpiresAt())).isInstanceOf(IllegalArgumentException.class);
        Order expired = expired();
        assertThatThrownBy(() -> ExpiredReviewClosure.record(expired, failure(expired, "DECLINED"), expired.getExpiresAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpiredReviewClosure.record(expired, result(completed(expired)), expired.getExpiresAt())).isInstanceOf(IllegalArgumentException.class);
        PaymentResult otherOrder = failure(expired(), "REVIEW_CLOSED");
        assertThatThrownBy(() -> ExpiredReviewClosure.record(expired, otherOrder, expired.getExpiresAt())).isInstanceOf(BusinessException.class);
    }

    @Test void theKeptIdentityAcceptsOnlyTheSameResultOfTheSameOrder() {
        Order expired = expired();
        PaymentResult closed = failure(expired, "REVIEW_CLOSED");
        Instant at = expired.getExpiresAt().plusSeconds(1);
        ExpiredReviewClosure kept = ExpiredReviewClosure.record(expired, closed, at);
        assertThat(kept.getOrderNo()).isEqualTo(expired.getOrderNo());
        assertThat(kept.getEventId()).isEqualTo(closed.eventId());
        assertThat(kept.getPaymentId()).isEqualTo(closed.paymentId());
        assertThat(kept.getReasonCode()).isEqualTo("REVIEW_CLOSED");
        assertThat(kept.getReason()).isEqualTo(closed.reason());
        assertThat(kept.getOccurredAt()).isEqualTo(closed.occurredAt());
        assertThat(kept.getRecordedAt()).isEqualTo(at);
        kept.requireSame(expired, closed);
        PaymentResult[] changed = {
                new PaymentResult(id(), closed.occurredAt(), closed.orderNo(), closed.requestEventId(), closed.paymentId(), closed.buyerSubject(),
                        closed.amount(), closed.currency(), OrderStatus.FAILED, "REVIEW_CLOSED", closed.reason()),
                new PaymentResult(closed.eventId(), closed.occurredAt(), closed.orderNo(), closed.requestEventId(), id(), closed.buyerSubject(),
                        closed.amount(), closed.currency(), OrderStatus.FAILED, "REVIEW_CLOSED", closed.reason()),
                new PaymentResult(closed.eventId(), closed.occurredAt(), closed.orderNo(), closed.requestEventId(), closed.paymentId(), closed.buyerSubject(),
                        closed.amount(), closed.currency(), OrderStatus.FAILED, "REVIEW_CLOSED", "changed reason"),
                new PaymentResult(closed.eventId(), closed.occurredAt().plusSeconds(1), closed.orderNo(), closed.requestEventId(), closed.paymentId(),
                        closed.buyerSubject(), closed.amount(), closed.currency(), OrderStatus.FAILED, "REVIEW_CLOSED", closed.reason()),
                new PaymentResult(closed.eventId(), closed.occurredAt(), closed.orderNo(), closed.requestEventId(), closed.paymentId(), closed.buyerSubject(),
                        closed.amount(), closed.currency(), OrderStatus.FAILED, "DECLINED", closed.reason())};
        for (PaymentResult value : changed)
            assertThatThrownBy(() -> kept.requireSame(expired, value)).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        Order another = expired();
        PaymentResult sameEventElsewhere = new PaymentResult(closed.eventId(), closed.occurredAt(), another.getOrderNo(), another.getRequestEventId(),
                closed.paymentId(), another.getBuyerSubject(), another.getAmount(), another.getCurrency(), OrderStatus.FAILED, "REVIEW_CLOSED", closed.reason());
        assertThatThrownBy(() -> kept.requireSame(another, sameEventElsewhere)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    private static Order expired() {
        Order value = order();
        assertThat(value.expire(value.getExpiresAt())).isTrue();
        return value;
    }
    private static PaymentResult failure(Order value, String reasonCode) {
        return new PaymentResult(id(), value.getExpiresAt(), value.getOrderNo(), value.getRequestEventId(), id(), value.getBuyerSubject(),
                value.getAmount(), value.getCurrency(), OrderStatus.FAILED, reasonCode, "Payment approval review closed with PG evidence CANCELLED");
    }
}
