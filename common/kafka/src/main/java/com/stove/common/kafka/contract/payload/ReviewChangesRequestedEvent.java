package com.stove.common.kafka.contract.payload;

import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import java.time.Instant;
import java.util.UUID;

/**
 * 심사 자료 수정 요청 사실을 다른 서비스에 전달하는 이벤트.
 *
 * @param eventId 서비스 간 전달 시 유지하는 이벤트 고유 식별자.
 * @param occurredAt 업무 행동을 기록한 시각.
 * @param reviewId 프로젝트 단위 심사 요청 식별자.
 * @param submissionId 심사할 자료와 빌드를 묶은 제출물 식별자.
 * @param reviewType 등급·상점·빌드 등 심사할 업무 유형.
 * @param productCode 서비스 간 상품을 연결하는 고유 코드.
 * @param reasonCode 처리 결과 또는 요청 사유를 구분하는 코드.
 * @param feedback 창작자에게 전달할 수정 요청 내용.
 */
public record ReviewChangesRequestedEvent(
        String eventId, Instant occurredAt,
        Long reviewId, Long submissionId, String reviewType,
        String productCode, String reasonCode, String feedback
) implements DomainEvent {
    public static ReviewChangesRequestedEvent of(Long reviewId, Long submissionId, String reviewType,
                                                 String productCode, String reasonCode, String feedback) {
        return new ReviewChangesRequestedEvent(UUID.randomUUID().toString(), Instant.now(), reviewId,
                submissionId, reviewType, productCode, reasonCode, feedback);
    }
    @Override public String eventType() { return EventType.REVIEW_CHANGES_REQUESTED; }
    @Override public String topic() { return Topics.REVIEW; }
    @Override public String partitionKey() { return productCode; }
}
