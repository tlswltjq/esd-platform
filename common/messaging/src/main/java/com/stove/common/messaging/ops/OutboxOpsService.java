package com.stove.common.messaging.ops;

import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEAD 로 떨어진 Outbox 이벤트를 조회하고 되살린다.
 *
 * <p>{@link OutboxEvent#requeue()} 는 처음부터 있었고 주석도 명확했다 —
 * "회수 경로가 없으면 장애가 길어진 순간 유실 방지 장치가 유실의 원인이 된다."
 * 그런데 <b>그 메서드를 부르는 코드가 테스트 말고는 없었다.</b> 되살리려면 운영자가
 * 프로덕션 DB 에 직접 UPDATE 를 쳐야 했다는 뜻이다.
 *
 * <p>이 서비스가 그 문을 단다. 로직은 새로 만들지 않았다 — 이미 있던 것에 진입점을 붙였을 뿐이다.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxOpsService {

    private final OutboxEventRepository repository;

    /**
     * 발행을 포기한 이벤트 목록.
     *
     * <p>페이징하지 않는다. DEAD 는 <b>정상이라면 0건</b>이고, 수천 건이 쌓였다면 목록을 넘기는 것보다
     * 원인을 먼저 봐야 하는 상황이다. 페이저가 필요해졌다는 것 자체가 신호다.
     */
    @Transactional(readOnly = true)
    public List<DeadEventResponse> deadEvents() {
        return repository.findByStatusOrderByIdAsc(OutboxStatus.DEAD).stream()
                .map(DeadEventResponse::from)
                .toList();
    }

    /**
     * 한 건을 발행 대기로 되돌린다.
     *
     * <p>{@code requeue()} 는 DEAD 가 아니면 아무 일도 하지 않으므로, 이미 나간 이벤트에
     * 실수로 걸어도 중복 발행이 되지 않는다. 그 판정을 여기서 다시 하지 않는다 —
     * 상태 전이 규칙은 도메인이 소유한다.
     */
    @Transactional
    public boolean requeue(String eventId) {
        return repository.findByStatusOrderByIdAsc(OutboxStatus.DEAD).stream()
                .filter(event -> event.getEventId().equals(eventId))
                .findFirst()
                .map(event -> {
                    event.requeue();
                    log.warn("운영 회수 — outbox 이벤트를 발행 대기로 되돌린다 eventId={} type={}",
                            event.getEventId(), event.getEventType());
                    return true;
                })
                .orElse(false);
    }

    /**
     * DEAD 전부를 되돌린다. 브로커 장애처럼 <b>원인이 하나였던</b> 경우를 위한 것이다.
     *
     * @return 되돌린 건수
     */
    @Transactional
    public int requeueAll() {
        List<OutboxEvent> dead = repository.findByStatusOrderByIdAsc(OutboxStatus.DEAD);
        dead.forEach(OutboxEvent::requeue);
        if (!dead.isEmpty()) {
            log.warn("운영 회수 — outbox DEAD {}건을 일괄로 되돌린다", dead.size());
        }
        return dead.size();
    }
}
