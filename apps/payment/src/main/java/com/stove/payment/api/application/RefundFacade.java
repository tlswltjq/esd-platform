package com.stove.payment.api.application;

import com.stove.payment.core.domain.PaymentCancellation;
import com.stove.payment.core.port.PgClient;
import com.stove.payment.core.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 환불 오케스트레이션.
 *
 * <p>여기서 조율이 필요한 이유는 하나다 — <b>PG 환불은 되돌릴 수 없고, 트랜잭션은 되돌릴 수 있다.</b>
 * 둘을 한 트랜잭션에 넣으면 뒤쪽이 실패했을 때 돈만 나가고 장부는 그대로인 상태가 생긴다.
 * 그래서 순서를 이렇게 고정한다.
 *
 * <ol>
 *   <li>의도 기록 커밋 — {@code PAID → CANCELING}</li>
 *   <li>PG 환불 (트랜잭션 밖)</li>
 *   <li>확정 커밋 — {@code CANCELING → CANCELED} + {@code PaymentCancelled} 적재</li>
 * </ol>
 *
 * <p>어느 단계에서 멈추든 결과가 관측 가능하다. 1번 전이면 아무 일도 없었고,
 * 2번에서 멈추면 {@code CANCELING} 으로 남아 재시도 대상이 된다.
 * 3번에서 멈춰도 마찬가지이며, PG 취소가 {@code pgTxId} 기준 멱등이라 재시도가 이중 환불이 되지 않는다.
 *
 * <p>트랜잭션을 열지 않는다 — 동기 외부 호출이 쓰기 트랜잭션 안으로 들어오면 안 되기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundFacade {

    private final PaymentService paymentService;
    private final PgClient pgClient;

    /** 사용자 요청 환불 */
    public void refund(String orderNo, String reason) {
        settle(paymentService.beginCancel(orderNo, reason), orderNo, reason);
    }

    /** Saga 보상 환불 — license 지급 최종 실패로 들어온다 */
    public void compensate(String eventId, String eventType, String orderNo, String reason) {
        settle(paymentService.beginCompensation(eventId, eventType, orderNo, reason), orderNo, reason);
    }

    private void settle(PaymentCancellation cancellation, String orderNo, String reason) {
        if (!cancellation.pgRefundRequired()) {
            return;
        }
        pgClient.cancel(cancellation.pgTxId(), cancellation.amount(), reason);
        paymentService.completeCancel(orderNo, reason);
    }
}
