package com.stove.payment.core.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;

/**
 * 사람이 지시하지 않은 환불의 수.
 *
 * <p><b>알람을 걸 자리가 필요해서 둔다.</b> 결제창 만료 자동 환불은 사용자 요청도, 운영자 조작도
 * 아닌 <b>시스템이 스스로 돈을 되돌리는</b> 유일한 HTTP 경로다. 이런 것이 조용히 늘어나면
 * 그건 PG 세션 설정이 어긋났거나 우리 창이 잘못 잡혔다는 신호인데, 아무도 보지 않으면
 * <b>사용자만 "결제했는데 취소됐다"를 겪고 우리는 모른다.</b>
 *
 * <p>{@code common:kafka} 의 {@code DeadLetterMetrics} 와 같은 판단이다 —
 * 되돌릴 수 있게 만드는 것과 되돌려야 한다고 알리는 것은 별개의 일이다.
 *
 * <p>노출 지표
 * <ul>
 *   <li>{@code stove.payment.auto-refunded} — 시스템이 스스로 되돌린 결제 수(사유 태그)</li>
 *   <li>{@code stove.payment.refund-resumed} — 중단된 취소를 재개한 수</li>
 *   <li>{@code stove.payment.canceling} — <b>지금 몇 건이 "돈이 나갔는지 모르는" 상태인가</b>.
 *       카운터가 아니라 게이지여야 하는 이유는, 재개가 계속 실패하면 카운터는 늘어나는데
 *       실제로 해소된 것은 하나도 없기 때문이다</li>
 *   <li>{@code stove.payment.canceling.stale} — 그중 <b>예산을 넘긴</b> 건수.
 *       <b>알람은 이쪽에 건다.</b> 전체 {@code CANCELING} 은 정상 환불이 진행 중인 몇 초 동안에도
 *       오르내리므로 거기에 알람을 걸면 잡음이 되고, 잡음은 사람이 알람을 무시하는 법을 가르친다</li>
 *   <li>{@code stove.payment.refund-resume-failed} — 재개가 <b>실패한</b> 수.
 *       성공만 세면 "계속 실패하는 중" 과 "대상이 없었다" 가 지표에서 같은 모습이다</li>
 * </ul>
 */
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry, PaymentRepository repository, PaymentProperties properties) {
        this.registry = registry;
        // 스크레이프 시점에 센다. idx_payment_status (status, id) 를 그대로 탄다.
        registry.gauge("stove.payment.canceling", repository,
                repo -> repo.countByStatus(PaymentStatus.CANCELING));
        // 예산 초과분. 기준 시각을 스크레이프 시점에 다시 계산해야 하므로 람다 안에서 now 를 읽는다 —
        // 밖에서 한 번 계산하면 그 값이 고정되어 시간이 흐르지 않는다.
        registry.gauge("stove.payment.canceling.stale", repository,
                repo -> repo.countByStatusAndCancelingSinceBefore(
                        PaymentStatus.CANCELING, Instant.now().minus(properties.refundBudget())));
    }

    /** 중단됐던 취소를 재개해 확정까지 보낸 수. 게이지가 줄어드는 이유를 설명하는 값이다. */
    public void recordRefundResumed() {
        Counter.builder("stove.payment.refund-resumed")
                .description("중단된 취소(CANCELING)를 재개해 확정한 수")
                .register(registry)
                .increment();
    }

    /**
     * 재개가 실패한 수.
     *
     * <p>성공 카운터만으로는 <b>PG 연동이 나빠지는 것을 볼 수 없다.</b> 실패는 로그에만 있었고
     * 로그를 세지 않으면 알 수 없었다 — 이 값과 게이지를 같이 보면
     * "몇 건이 불확실한가" 와 "그게 왜 안 풀리는가" 가 같은 화면에 놓인다.
     */
    public void recordRefundResumeFailed() {
        Counter.builder("stove.payment.refund-resume-failed")
                .description("중단된 취소 재개가 실패한 수 — PG 연동 품질을 보는 창")
                .register(registry)
                .increment();
    }

    /**
     * Saga 보상 환불이 실제로 일어난 수.
     *
     * <p><b>0 도 값이다.</b> 이 경로는 운영에서 한 번도 지나가지 않았는데(실측 0건),
     * 지금까지는 그 사실조차 <b>outbox 테이블을 직접 세어야</b> 알 수 있었다.
     * 지표가 되면 "안 쓰이는 경로" 라는 판단을 추론이 아니라 관측으로 할 수 있다 —
     * 그리고 <b>처음 한 번 오르는 순간</b>이 D-027 이 좁힌 조건을 다시 볼 시점이다(#46).
     */
    public void recordCompensation() {
        Counter.builder("stove.payment.compensated")
                .description("license 지급 실패로 되돌린 결제 수 — 0 이 계속되면 보상 경로를 다시 본다")
                .register(registry)
                .increment();
    }

    /** @param reason 왜 시스템이 스스로 환불했는가. 카디널리티는 사유 종류만큼(고정)이다. */
    public void recordAutoRefund(String reason) {
        Counter.builder("stove.payment.auto-refunded")
                .description("사용자·운영자 지시 없이 시스템이 되돌린 결제 수 — 운영 확인 대상")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
