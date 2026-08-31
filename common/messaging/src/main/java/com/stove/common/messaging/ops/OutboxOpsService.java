package com.stove.common.messaging.ops;

import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEvent.OutboxStatus;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/** DEAD 로 떨어진 Outbox 이벤트를 조회하고 되살린다. docs/code-notes.md */
@Slf4j
@RequiredArgsConstructor
public class OutboxOpsService {

    private final OutboxEventRepository repository;

    /** 발행을 포기한 이벤트 목록. <b>페이징하지 않는다</b> — 정상이라면 0건이다. */
    @Transactional(readOnly = true)
    public List<DeadEventResponse> deadEvents() {
        return repository.findByStatusOrderByIdAsc(OutboxStatus.DEAD).stream()
                .map(DeadEventResponse::from)
                .toList();
    }

    /** 한 건을 발행 대기로 되돌린다. 상태 판정은 도메인이 소유하므로 여기서 다시 하지 않는다. */
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
