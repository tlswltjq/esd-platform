package com.stove.payment.core.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
 * <p>노출 지표: {@code stove.payment.auto-refunded}
 * (Prometheus 에서는 {@code stove_payment_auto_refunded_total})
 */
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
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
