package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * payment → order(실패 종료). PG 가 승인을 거절해 결제가 끝났음을 알린다.
 *
 * <p>취소({@link PaymentCancelledEvent})와 다르다 — 취소는 승인된 돈을 되돌리는 것이라
 * license 회수와 settlement 역산이 따라붙지만, 여기서는 <b>돈이 움직인 적이 없다.</b>
 * 그래서 구독자는 order 하나뿐이고 환불 금액도 싣지 않는다.
 *
 * <p>{@code reasonCode} 는 PG 가 준 거절 코드를 그대로 옮긴다. 사람이 읽는 {@code reason}
 * 과 나눠 둬야 "한도초과가 몇 건인지" 같은 집계가 가능하다.
 */
public record PaymentFailedEvent(
        String eventId,
        Instant occurredAt,
        Long paymentId,
        String orderNo,
        Long memberId,
        String reasonCode,
        String reason
) implements DomainEvent {

    public static PaymentFailedEvent of(Long paymentId, String orderNo, Long memberId,
                                        String reasonCode, String reason) {
        return new PaymentFailedEvent(UUID.randomUUID().toString(), Instant.now(),
                paymentId, orderNo, memberId, reasonCode, reason);
    }

    @Override
    public String eventType() {
        return EventType.PAYMENT_FAILED;
    }

    @Override
    public String topic() {
        return Topics.PAYMENT;
    }

    @Override
    public String partitionKey() {
        return orderNo;
    }
}
