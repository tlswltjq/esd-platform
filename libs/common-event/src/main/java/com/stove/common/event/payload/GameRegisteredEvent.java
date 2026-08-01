package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/** [등록] studio → review : 창작자가 심의를 신청함 */
public record GameRegisteredEvent(
        String eventId,
        Instant occurredAt,
        Long gameId,
        String productCode,
        String title,
        Long sellerId,
        long price,
        String currency,
        /** 자체등급분류사업자 경로 여부. true 면 게임위 접수 없이 내부 심사로 처리된다. */
        boolean selfRated
) implements DomainEvent {

    public static GameRegisteredEvent of(Long gameId, String productCode, String title, Long sellerId,
                                         long price, String currency, boolean selfRated) {
        return new GameRegisteredEvent(UUID.randomUUID().toString(), Instant.now(),
                gameId, productCode, title, sellerId, price, currency, selfRated);
    }

    @Override
    public String eventType() {
        return EventType.GAME_REGISTERED;
    }

    @Override
    public String topic() {
        return Topics.STUDIO;
    }

    @Override
    public String partitionKey() {
        return productCode;
    }
}
