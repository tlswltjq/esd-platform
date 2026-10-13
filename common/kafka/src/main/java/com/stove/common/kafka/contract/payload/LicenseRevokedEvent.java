package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 *  license → download : 소유권 회수(다운로드 권한 박탈)
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param orderNo 주문·결제·이용권·정산을 연결하는 주문번호.
 * @param memberId 구매자를 식별하는 계정 ID.
 * @param productIds 이용권 지급 또는 회수 대상 상품 식별자 목록.
 * @param reason 처리 결과 또는 요청 사유에 대한 설명.
 */
public record LicenseRevokedEvent(
        String eventId,
        Instant occurredAt,
        String orderNo,
        Long memberId,
        List<Long> productIds,
        String reason
) implements DomainEvent {

    public static LicenseRevokedEvent of(String orderNo, Long memberId, List<Long> productIds, String reason) {
        return new LicenseRevokedEvent(UUID.randomUUID().toString(), Instant.now(),
                orderNo, memberId, productIds, reason);
    }

    @Override
    public String eventType() {
        return EventType.LICENSE_REVOKED;
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
