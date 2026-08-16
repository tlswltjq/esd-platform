package com.stove.order.api.scheduler;

import com.stove.order.core.service.OrderExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제를 시작조차 하지 않은 주문을 닫는다.
 *
 * <p><b>왜 스케줄러인가</b> — {@code CREATED} 이후 상태는 전부 결제 결과 이벤트로 바뀐다.
 * 결제를 아예 시작하지 않으면 그 이벤트가 없으므로 <b>상태를 바꿔 줄 사건 자체가 없다.</b>
 * 시간이 그 사건을 대신한다.
 *
 * <p><b>왜 한 번에 다 안 하는가</b> — 실측으로 밀린 것이 98,750건이었다. 한 트랜잭션에 넣으면
 * 락과 언두 로그가 그만큼 커진다. 목적은 밀린 것을 한 번에 없애는 것이 아니라
 * <b>늘지 않게 하는 것</b>이므로, 회차마다 배치 크기만큼만 집고 나머지는 다음 회차로 넘긴다.
 * 밀린 분량이 다 빠지는 데 걸리는 시간은 {@code stove.order.expirable} 게이지가 말해 준다.
 *
 * <p>주기를 1분으로 잡은 것은 급해서가 아니다. <b>배치 크기와 곱해 처리량이 정해지기 때문</b>이고,
 * 500 × 60분 = 시간당 3만 건이면 실측 유입(하루 1~3건)의 몇 자릿수 위다.
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
