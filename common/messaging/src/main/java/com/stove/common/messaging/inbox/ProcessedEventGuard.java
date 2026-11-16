package com.stove.common.messaging.inbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컨슈머 멱등 가드. <b>비즈니스 처리와 같은 트랜잭션에서 마킹해야 한다</b> —
 * 갈리면 "마킹은 됐는데 처리는 롤백" 된 이벤트가 영구 유실된다.
 *
 * <p>이미 처리한 이벤트를 다시 받으면 {@value #DUPLICATES_METRIC}{consumer_group, event_type}을 센다.
 * 브로커가 얼마나 재전달하는지를 기술마다 비교하는 지표다([결정 204] D 재전달 수).
 */
@Slf4j
public class ProcessedEventGuard {

    public static final String DUPLICATES_METRIC = "stove.inbox.duplicates";

    private final ProcessedEventRepository repository;
    private final MeterRegistry meterRegistry;

    /** 레지스트리는 조립 쪽(자동 구성·테스트)이 넘긴다(AGENTS.md 3절, PR #204 R3). */
    public ProcessedEventGuard(ProcessedEventRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return 최초 수신이면 true(계속 처리), 이미 처리한 이벤트면 false(스킵)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(String eventId, String consumerGroup, String eventType) {
        if (eventId == null || eventId.isBlank()) {
            // 여기서 던져야 한다 — 제약까지 미루면 비즈니스 로직이 실행된 뒤 롤백된다.
            throw new IllegalArgumentException(
                    "eventId 없이는 중복 여부를 판단할 수 없다 group=%s type=%s".formatted(consumerGroup, eventType));
        }
        if (repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            log.info("중복 이벤트 스킵 eventId={} type={} group={}", eventId, eventType, consumerGroup);
            Counter.builder(DUPLICATES_METRIC)
                    .description("이미 처리한 이벤트를 다시 받은 횟수")
                    .tag("consumer_group", consumerGroup)
                    .tag("event_type", eventType == null ? "unknown" : eventType)
                    .register(meterRegistry)
                    .increment();
            return false;
        }
        repository.save(ProcessedEvent.of(eventId, consumerGroup, eventType));
        return true;
    }
}
