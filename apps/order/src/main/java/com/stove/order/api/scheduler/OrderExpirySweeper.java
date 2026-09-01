package com.stove.order.api.scheduler;

import com.stove.order.core.service.OrderExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제를 시작조차 하지 않은 주문을 닫는다. <b>주기 × 배치 크기가 곧 처리량</b>이라
 * 1분은 급해서 정한 값이 아니다. docs/code-notes.md
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirySweeper {

    private final OrderExpiryService orderExpiryService;

    @Scheduled(fixedDelayString = "${stove.order.expire-sweep-interval-ms:60000}")
    @SchedulerLock(name = "order-expire-stale", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void expireStaleOrders() {
        int expired = orderExpiryService.expireStaleOrders();
        if (expired > 0) {
            log.info("만료 스윕: {}건", expired);
        }
    }
}
