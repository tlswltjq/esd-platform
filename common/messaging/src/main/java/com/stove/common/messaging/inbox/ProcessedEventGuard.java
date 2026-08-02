package com.stove.common.messaging.inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컨슈머 멱등 가드.
 *
 * <p>비즈니스 처리와 <b>같은 트랜잭션</b>에서 마킹하는 것이 핵심이다.
 * 별도 트랜잭션으로 커밋해 버리면 "마킹은 됐는데 처리는 롤백" 된 이벤트가 영구 유실된다.
 * 동시에 같은 이벤트가 두 번 들어오면 유니크 제약으로 한쪽이 롤백되고, 재시도 시 여기서 걸러진다.
 */
@Slf4j
@RequiredArgsConstructor
public class ProcessedEventGuard {

    private final ProcessedEventRepository repository;

    /**
     * @return 최초 수신이면 true(계속 처리), 이미 처리한 이벤트면 false(스킵)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String eventId, String consumerGroup, String eventType) {
        if (eventId == null || eventId.isBlank()) {
            // 판단 근거가 없는데 '처음 본 이벤트'라고 답하면 부수효과가 그대로 일어난다.
            // 실패를 event_id NOT NULL 제약까지 미루면 비즈니스 로직이 실행된 뒤 롤백된다.
            throw new IllegalArgumentException(
                    "eventId 없이는 중복 여부를 판단할 수 없다 group=%s type=%s".formatted(consumerGroup, eventType));
        }
        if (repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            log.info("중복 이벤트 스킵 eventId={} type={} group={}", eventId, eventType, consumerGroup);
            return false;
        }
        repository.save(ProcessedEvent.of(eventId, consumerGroup, eventType));
        return true;
    }
}
