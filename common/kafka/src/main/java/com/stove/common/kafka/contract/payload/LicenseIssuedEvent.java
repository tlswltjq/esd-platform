package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 *  license → order : 라이브러리 지급 완료(Saga 정상 종료)
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param memberId 구매자를 식별하는 계정 ID.
 * @param productIds 이용권 지급 또는 회수 대상 상품 식별자 목록.
 */
public record LicenseIssuedEvent(
        String eventId,
        Instant occurredAt,
        String orderNo,
        Long memberId,
        List<Long> productIds
) implements DomainEvent {

    public static LicenseIssuedEvent of(String orderNo, Long memberId, List<Long> productIds) {
        return new LicenseIssuedEvent(UUID.randomUUID().toString(), Instant.now(), orderNo, memberId, productIds);
    }

    @Override
    public String eventType() {
        return EventType.LICENSE_ISSUED;
    }

    @Override
    public String topic() {
        return Topics.LICENSE;
    }

    @Override
    public String partitionKey() {
        return orderNo;
    }
}
