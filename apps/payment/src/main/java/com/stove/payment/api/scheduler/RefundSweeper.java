package com.stove.payment.api.scheduler;

import com.stove.payment.api.application.RefundFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 중단된 취소({@code CANCELING})를 이어서 끝낸다. <b>주기와 예산은 다른 값이다</b> —
 * 1분마다 깨어나지만 각 건의 다음 시도 시각은 {@code RefundRetryPolicy} 가 정한다.
 * docs/code-notes.md
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundSweeper {

    private final RefundFacade refundFacade;

    @Scheduled(fixedDelayString = "${stove.payment.refund-sweep-interval-ms:60000}")
    @SchedulerLock(name = "payment-resume-stranded-refunds", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void resumeStrandedRefunds() {
        int resumed = refundFacade.resumeStranded();
        if (resumed > 0) {
            log.warn("중단된 취소 {}건을 확정까지 보냈다", resumed);
        }
    }
}
