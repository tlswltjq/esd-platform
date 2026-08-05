package com.stove.settlement.api.scheduler;

import com.stove.settlement.api.application.SettlementCloseFacade;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 월 정산 마감 배치. 매월 1일 03시에 전월분을 확정한다.
 *
 * <p><b>단일 실행 보장</b> — 인스턴스가 여러 대면 {@code @Scheduled} 는 대수만큼 동시에 발화한다.
 * {@code @SchedulerLock} 이 MySQL 락으로 그중 하나만 통과시킨다.
 *
 * <ul>
 *   <li>{@code lockAtMostFor} — 락을 잡은 인스턴스가 죽어도 이 시간이 지나면 풀린다.
 *       마감 실행 시간보다 넉넉히 길게 잡되, 다음 발화(한 달 뒤)보다는 짧아야 한다</li>
 *   <li>{@code lockAtLeastFor} — 실행이 순식간에 끝나도 이 시간 동안은 락을 쥔다.
 *       인스턴스 간 시계 오차로 두 번째 인스턴스가 뒤늦게 발화해 다시 도는 것을 막는다</li>
 * </ul>
 *
 * <p>락은 <b>동시 실행 창만</b> 닫는다. 마감 중간에 실패했을 때의 안전성은
 * {@link SettlementCloseFacade} 의 단계 분리가 책임진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementBatch {

    private final SettlementCloseFacade settlementCloseFacade;

    @Scheduled(cron = "${stove.settlement.close-cron:0 0 3 1 * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-close-month", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void closePreviousMonth() {
        YearMonth target = YearMonth.from(LocalDate.now()).minusMonths(1);
        log.info("정산 마감 배치 시작 month={}", target);
        settlementCloseFacade.closeMonth(target);
    }
}
