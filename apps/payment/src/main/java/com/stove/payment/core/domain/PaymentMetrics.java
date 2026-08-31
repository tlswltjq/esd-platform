package com.stove.payment.core.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;

/** 결제 지표. 각 값이 무엇을 말하는지와 알람을 어디에 거는지는 docs/code-notes.md */
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry, PaymentRepository repository, PaymentProperties properties) {
        this.registry = registry;
        // 스크레이프 시점에 센다. idx_payment_status (status, id) 를 탄다.
        registry.gauge("stove.payment.canceling", repository,
                repo -> repo.countByStatus(PaymentStatus.CANCELING));
        // now 는 반드시 람다 안에서 읽는다 — 밖에서 계산하면 시간이 흐르지 않는다.
        registry.gauge("stove.payment.canceling.stale", repository,
                repo -> repo.countByStatusAndCancelingSinceBefore(
                        PaymentStatus.CANCELING, Instant.now().minus(properties.refundBudget())));
    }

    /** 중단됐던 취소를 재개해 확정까지 보낸 수. */
    public void recordRefundResumed() {
        Counter.builder("stove.payment.refund-resumed")
                .description("중단된 취소(CANCELING)를 재개해 확정한 수")
                .register(registry)
                .increment();
    }

    /** 재개가 실패한 수. 성공만 세면 PG 연동이 나빠지는 것을 볼 수 없다. */
    public void recordRefundResumeFailed() {
        Counter.builder("stove.payment.refund-resume-failed")
                .description("중단된 취소 재개가 실패한 수 — PG 연동 품질을 보는 창")
                .register(registry)
                .increment();
    }

    /** Saga 보상 환불이 실제로 일어난 수. <b>0 도 값이다</b> — docs/code-notes.md */
    public void recordCompensation() {
        Counter.builder("stove.payment.compensated")
                .description("license 지급 실패로 되돌린 결제 수 — 0 이 계속되면 보상 경로를 다시 본다")
                .register(registry)
                .increment();
    }

    /** @param reason 왜 시스템이 스스로 환불했는가. 카디널리티는 사유 종류만큼 고정이다. */
    public void recordAutoRefund(String reason) {
        Counter.builder("stove.payment.auto-refunded")
                .description("사용자·운영자 지시 없이 시스템이 되돌린 결제 수 — 운영 확인 대상")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
