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

    public void refund(String orderNo, String reason) {
        settle(paymentService.beginCancel(orderNo, reason), orderNo, reason);
    }

    /**
     * Saga 보상 환불 — license 지급 최종 실패로 들어온다.
     *
     * <p><b>이 경로는 운영에서 한 번도 지나가지 않았다</b>(실측: license outbox 4,153건 중
     * {@code LicenseIssueFailed} 0건). D-027 이 보상 조건을 "재시도 소진 + 저장소 탓이 아님 +
     * 실제로 지급되지 않음" 으로 좁혔고, 저장소 장애는 전부 DLT 로 가기 때문이다.
     *
     * <p><b>그래도 지우지 않는다.</b> 0 은 "필요 없다" 가 아니라 <b>"그 실패가 아직 안 났다"</b> 는
     * 뜻이다 — 지금 이 경로를 지우면 계약({@code LicenseIssueFailedEvent})과 소비 경로가 함께
     * 사라지고, 비저장소 실패가 처음 생기는 날 <b>되돌릴 수단이 없는 채로 그 사실을 알게 된다.</b>
     * 되돌릴 수 있는 것을 지우는 판단은 그것이 필요해지는 시점을 예측할 수 있을 때만 옳다(#46).
     *
     * <p>대신 <b>살아 있다는 것을 무엇이 보장하는가</b>에 답한다 — 이 카운터가 "몇 번 지나갔나" 를
     * 세고(0 도 값이다), {@code PaymentCompensationTest} 가 실 인프라 위에서 이 경로를 지난다.
     * 예전에는 단위 테스트뿐이라 <b>배선이 끊겨도 아무도 몰랐다.</b>
     */
    public void compensate(String eventId, String eventType, String orderNo, String reason) {
        PaymentCancellation cancellation = paymentService.beginCompensation(eventId, eventType, orderNo, reason);
        // 중복 수신이나 상태 불일치로 아무것도 하지 않은 경우는 세지 않는다 —
        // "보상이 몇 번 일어났나" 를 물어야 하는데 배달 횟수를 세면 그 값이 흐려진다.
        if (cancellation.pgRefundRequired()) {
            paymentMetrics.recordCompensation();
        }
        settle(cancellation, orderNo, reason);
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
     * <p><b>재시도에 예산이 있다.</b> 예전에는 대상을 무조건 매 회차 다시 걸었다 — PG 가 오래
     * 죽어 있으면 같은 건에 같은 간격으로 영원히 요청이 나갔고, 그건 <b>복구 중인 PG 를 계속
     * 두드리는 것</b>이었다. 이제 {@link RefundRetryPolicy} 가 다음 시도를 미룬다(2→4→…→30분).
     *
     * <p><b>포기 상태는 만들지 않는다.</b> Outbox 는 예산이 소진되면 {@code DEAD} 로 보내지만
     * (D-003), {@code CANCELING} 은 "돈이 나갔는지 불확실" 이라는 뜻이라 포기할 대상이 아니다.
     * 종단 상태로 옮기는 순간 <b>불확실이 해소된 것처럼 보이고 아무도 다시 보지 않는다.</b>
     * 그래서 예산은 재시도를 멈추는 값이 아니라 <b>사람을 부르는 값</b>이고,
     * 그 자리를 {@code stove.payment.canceling.stale} 게이지가 맡는다.
     *
     * @return 확정까지 보낸 건수
     */
    public int resumeStranded() {
        List<StrandedCancellation> stranded = paymentService.findStrandedCancellations();
        if (stranded.isEmpty()) {
            return 0;
        }
        log.warn("중단된 취소 {}건을 재개한다", stranded.size());

        int resumed = 0;
        for (StrandedCancellation target : stranded) {
            // 성공하든 실패하든 다음 시도를 먼저 미뤄 둔다. 확정된 건은 completeCancel 이
            // 예약을 지우므로 다시 잡히지 않는다 — 순서를 뒤집으면 확정 직후 예약이 되살아난다.
            try {
                settle(paymentService.beginCancel(target.orderNo(), target.reason()),
                        target.orderNo(), target.reason());
                paymentMetrics.recordRefundResumed();
                resumed++;
            } catch (Exception e) {
                paymentMetrics.recordRefundResumeFailed();
                log.error("취소 재개 실패 {}회차 — 다음 회차에 다시 시도한다 orderNo={}",
                        target.attempts() + 1, target.orderNo(), e);
            } finally {
                scheduleNext(target.orderNo());
            }
        }
        return resumed;
    }

    /**
     * 다음 시도 예약은 실패해도 스윕을 멈추지 않는다.
     *
     * <p>예약이 깨지면 그 건은 다음 회차에 곧바로 다시 잡힌다 — 백오프를 잃을 뿐 정합성은
     * 그대로다. 반면 여기서 예외가 올라가면 <b>남은 건들이 시도조차 되지 않는다.</b>
     * 위 루프가 한 건의 실패로 멈추지 않게 만든 것과 같은 이유다.
     */
    private void scheduleNext(String orderNo) {
        try {
            paymentService.scheduleCancelRetry(orderNo);
        } catch (Exception e) {
            log.warn("다음 재개 예약 실패 — 다음 회차에 곧바로 다시 잡힌다 orderNo={}", orderNo, e);
        }
    }

    private void settle(PaymentCancellation cancellation, String orderNo, String reason) {
        if (!cancellation.pgRefundRequired()) {
            return;
        }
        pgClient.cancel(cancellation.pgTxId(), cancellation.amount(), reason);
        paymentService.completeCancel(orderNo, reason);
    }
}
