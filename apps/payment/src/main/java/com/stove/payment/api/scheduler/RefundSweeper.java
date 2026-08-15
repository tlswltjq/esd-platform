package com.stove.payment.api.scheduler;

import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.core.domain.PaymentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 중단된 취소를 이어서 끝낸다.
 *
 * <p><b>왜 필요한가</b> — 취소는 "의도 기록 → PG 환불 → 확정" 세 걸음이고, PG 환불이
 * 되돌릴 수 없는 외부 호출이라 트랜잭션 밖에 있다({@link RefundFacade} 주석). 그 사이에서 멈추면
 * {@code CANCELING} 으로 남는데, 그건 <b>돈이 나갔는지 불확실하다</b>는 뜻이다.
 *
 * <p>설계는 "재시도 대상으로 눈에 띈다" 까지였고 **재시도를 거는 쪽이 없었다.**
 * 관측 가능하게 만들어 둔 것과 실제로 해소되는 것은 다르다 — 사용자에게는 그동안
 * "환불했다는데 돈이 안 들어왔다" 로 보인다.
 *
 * <p><b>왜 지금 더 급해졌나</b> — 결제창 만료 자동 환불(D-029 후속)이 같은 세 걸음을 쓴다.
 * 사용자 요청 환불은 사람이 다시 누르기라도 하는데, 자동 환불은 <b>아무도 다시 누르지 않는다.</b>
 *
 * <p><b>단일 실행 보장</b> — 인스턴스가 여러 대면 같은 {@code CANCELING} 행을 동시에 집어
 * PG 취소를 대수만큼 부른다. {@code pgTxId} 기준 멱등이라 이중 환불은 아니지만,
 * 재개 로그와 이벤트가 겹쳐 <b>"몇 번 시도했나" 를 알 수 없게 된다</b> — 그 값이 곧
 * PG 연동이 정상인지 보는 창이다.
 *
 * <p>{@code lockAtMostFor} 를 폴링 주기보다 넉넉히 잡는다. 락을 쥔 인스턴스가 죽어도 이 시간이
 * 지나면 풀리고, 그 전에는 다른 인스턴스가 들어오지 않는다. {@code lockAtLeastFor} 는
 * 대상이 0건이라 순식간에 끝났을 때 시계 오차로 두 번째 인스턴스가 곧바로 다시 도는 것을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundSweeper {

    private final RefundFacade refundFacade;
    private final PaymentProperties paymentProperties;

    @Scheduled(fixedDelayString = "${stove.payment.refund-sweep-interval-ms:60000}")
    @SchedulerLock(name = "payment-resume-stranded-refunds", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void resumeStrandedRefunds() {
        int resumed = refundFacade.resumeStranded(paymentProperties.refundResumeAfter());
        if (resumed > 0) {
            log.warn("중단된 취소 {}건을 확정까지 보냈다", resumed);
        }
    }
}
