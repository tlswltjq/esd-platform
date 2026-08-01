package com.stove.settlement.application;

import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 월 정산 마감 배치. 매월 1일 03시에 전월분을 확정한다.
 * 인스턴스가 여러 대인 환경에서는 ShedLock 등으로 단일 실행을 보장해야 한다(TODO).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementBatch {

    private final SettlementService settlementService;

    @Scheduled(cron = "${stove.settlement.close-cron:0 0 3 1 * *}", zone = "Asia/Seoul")
    public void closePreviousMonth() {
        YearMonth target = YearMonth.from(LocalDate.now()).minusMonths(1);
        log.info("정산 마감 배치 시작 month={}", target);
        settlementService.closeMonth(target);
    }
}
