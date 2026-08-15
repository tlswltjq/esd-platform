package com.stove.payment.api.application;

import com.stove.payment.core.domain.PaymentCancellation;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.port.PgClient;
import com.stove.payment.core.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 승인 콜백 오케스트레이션.
 *
 * <p>대부분의 승인은 조율할 것이 없다 — 한 트랜잭션에서 확정하고 이벤트를 적재하면 끝이다.
 * 파사드가 필요해진 것은 <b>결제창이 만료된 뒤 도착한 승인</b> 하나 때문이다.
 * 그 건은 "승인을 적는다 → PG 에 환불을 건다 → 취소를 확정한다"로 <b>외부 호출이 가운데 끼는</b>
 * 세 걸음이 되고, PG 환불은 되돌릴 수 없으므로 쓰기 트랜잭션 안에 들어올 수 없다.
 *
 * <p>순서와 이유는 {@link RefundFacade} 와 같다. 다른 것은 <b>1번 걸음이 취소가 아니라 승인</b>이라는
 * 점뿐이다 — 그래서 여기서는 승인이 먼저 커밋되고, 그 커밋이 곧
 * "PG 는 돈을 잡았고 우리도 그 사실을 안다"는 기록이 된다.
 *
 * <ol>
 *   <li>승인 확정 커밋 — {@code PENDING → PAID}, 만료면 이어서 {@code → CANCELING}</li>
 *   <li>PG 환불 (트랜잭션 밖)</li>
 *   <li>확정 커밋 — {@code CANCELING → CANCELED} + {@code PaymentCancelled} 적재</li>
 * </ol>
 *
 * <p>2번에서 멈추면 {@code CANCELING} 으로 남는다. 사용자 환불과 같은 자리이고 같은 성질이다 —
 * PG 취소가 {@code pgTxId} 기준 멱등이라 재시도가 이중 환불이 되지 않는다.
 * <b>다만 그 재시도를 자동으로 거는 장치는 아직 없다</b>({@code RefundFacade} 와 공통으로 남은 부분).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCallbackFacade {

    private final PaymentService paymentService;
    private final PgClient pgClient;

    public void approve(PgApproval approval) {
        PaymentCancellation cancellation = paymentService.handleApproval(approval);
        if (!cancellation.pgRefundRequired()) {
            return;
        }
        pgClient.cancel(cancellation.pgTxId(), cancellation.amount(), PaymentService.CHECKOUT_EXPIRED);
        paymentService.completeCancel(approval.orderNo(), PaymentService.CHECKOUT_EXPIRED);
        log.warn("결제창 만료 자동 환불 완료 orderNo={}", approval.orderNo());
    }
}
