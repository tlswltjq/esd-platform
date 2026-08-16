package com.stove.payment.api.application;

import com.stove.payment.core.domain.PaymentCancellation;
import com.stove.payment.core.domain.PaymentMetrics;
import com.stove.payment.core.domain.StrandedCancellation;
import java.util.List;
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
    private final PaymentMetrics paymentMetrics;

    /** 사용자 요청 환불 */
    public void refund(String orderNo, String reason) {
        settle(paymentService.beginCancel(orderNo, reason), orderNo, reason);
    }

    /** Saga 보상 환불 — license 지급 최종 실패로 들어온다 */
    public void compensate(String eventId, String eventType, String orderNo, String reason) {
        settle(paymentService.beginCompensation(eventId, eventType, orderNo, reason), orderNo, reason);
    }

    /**
     * 중단된 취소를 재개한다.
     *
     * <p><b>{@code CANCELING} 은 "돈이 나갔는지 불확실하다" 는 뜻</b>이고, 지금까지 그 상태를
     * 끝내는 것은 사람뿐이었다. 어느 단계에서 멈추든 관측 가능하게 만들어 두고
     * <b>정작 재개는 아무도 걸지 않는</b> 구조였다 — 이 클래스 주석이 "재시도 대상이 된다" 고
     * 적어 둔 그 재시도다.
     *
     * <p>재개가 안전한 근거는 {@link PgClient#cancel} 의 멱등 계약 하나다. 이미 취소된 거래에
     * 다시 요청해도 이중 환불이 되지 않으므로, <b>실제로 나갔는지 모르는 채로 다시 걸어도 된다.</b>
     * 그 계약이 없으면 이 메서드는 존재할 수 없다.
     *
     * <p>한 건의 실패가 다음 건을 막지 않는다. PG 가 죽어 있으면 전부 실패할 텐데,
     * 그때 첫 건에서 멈추면 나머지는 시도조차 안 된 것인지 실패한 것인지 구분되지 않는다.
     *
     * @return 확정까지 보낸 건수
     */
    public int resumeStranded(java.time.Duration olderThan) {
        List<StrandedCancellation> stranded =
                paymentService.findStrandedCancellations(olderThan);
        if (stranded.isEmpty()) {
            return 0;
        }
        log.warn("중단된 취소 {}건을 재개한다 — 확정되지 않은 채 {} 이상 남아 있었다",
                stranded.size(), olderThan);

        int resumed = 0;
        for (StrandedCancellation target : stranded) {
            try {
                settle(paymentService.beginCancel(target.orderNo(), target.reason()),
                        target.orderNo(), target.reason());
                paymentMetrics.recordRefundResumed();
                resumed++;
            } catch (Exception e) {
                log.error("취소 재개 실패 — 다음 회차에 다시 시도한다 orderNo={}", target.orderNo(), e);
            }
        }
        return resumed;
    }

    private void settle(PaymentCancellation cancellation, String orderNo, String reason) {
        if (!cancellation.pgRefundRequired()) {
            return;
        }
        pgClient.cancel(cancellation.pgTxId(), cancellation.amount(), reason);
        paymentService.completeCancel(orderNo, reason);
    }
}
