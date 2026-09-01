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
 * 환불 오케스트레이션. 순서는 "의도 기록 커밋 → PG 환불(트랜잭션 밖) → 확정 커밋" 이고
 * <b>이 클래스는 트랜잭션을 열지 않는다.</b> 근거는 docs/code-notes.md
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
     * <b>운영 실측 0건이지만 지우지 않는다</b>(#46, D-027). 근거는 docs/code-notes.md
     */
    public void compensate(String eventId, String eventType, String orderNo, String reason) {
        PaymentCancellation cancellation = paymentService.beginCompensation(eventId, eventType, orderNo, reason);
        // 아무것도 하지 않은 경우는 세지 않는다 — 배달 횟수가 아니라 보상 횟수를 묻는다.
        if (cancellation.pgRefundRequired()) {
            paymentMetrics.recordCompensation();
        }
        settle(cancellation, orderNo, reason);
    }

    /**
     * 중단된 취소를 재개한다. 안전한 근거는 {@code PgClient#cancel} 의 멱등 계약 하나이고,
     * <b>포기 상태는 만들지 않는다.</b> docs/code-notes.md
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
            // finally 에서 예약한다 — 순서를 뒤집으면 확정 직후 예약이 되살아난다.
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

    /** 예약 실패가 스윕을 멈추면 안 된다 — 남은 건들이 시도조차 되지 않는다. */
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
