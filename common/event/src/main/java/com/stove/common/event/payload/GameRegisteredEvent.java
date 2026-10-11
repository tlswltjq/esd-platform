package com.stove.common.event.payload;

import com.stove.common.event.DomainEvent;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 *  [등록] studio → review : 창작자가 심의를 신청함
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param gameId Studio 게임 프로젝트 식별자.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param title 게임 또는 상점 페이지에 표시할 제목.
 * @param sellerId 판매자를 나타내는 워크스페이스 식별자.
 * @param price 상품 한 개의 가격으로, 최소 화폐 단위의 정수.
 * @param currency 금액의 통화 코드.
 * @param selfRated 자체등급분류 경로로 신청하는지 여부.
 */
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
