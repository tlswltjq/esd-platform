package com.stove.studio.core.domain.notification;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 빌드 결과 웹훅 한 건의 발송 상태와 재시도 기록.
 */
@Entity
@Getter
@Table(name = "build_webhook_delivery")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildWebhookDelivery extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** 빌드 결과를 통보할 웹훅 구독 식별자. */
    @Column(nullable = false) private Long subscriptionId;
    /** 배포 파일을 나타내는 빌드 식별자. */
    @Column(nullable = false) private Long buildId;
    /** 전달하거나 처리한 업무 이벤트 종류. */
    @Column(nullable = false, length = 50) private String eventType;
    /** 전달할 이벤트 본문. */
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String payload;
    /** 웹훅의 발송 대기·재시도·성공·최종 실패 상태. */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private WebhookDeliveryStatus status;
    /** 성공 여부와 관계없이 수행한 발송 시도 횟수. */
    @Column(nullable = false) private int attemptCount;
    /** 다음 발송을 시도할 예정 시각. */
    @Column(nullable = false) private Instant nextAttemptAt;
    /** 웹훅 수신처에 전달을 완료한 시각. */
    private Instant deliveredAt;
    /** 마지막 처리 실패의 설명. */
    @Column(length = 500) private String lastError;

    public static BuildWebhookDelivery pending(Long subscriptionId, Long buildId,
                                               String eventType, String payload) {
        BuildWebhookDelivery delivery = new BuildWebhookDelivery();
        delivery.subscriptionId = subscriptionId;
        delivery.buildId = buildId;
        delivery.eventType = eventType;
        delivery.payload = payload;
        delivery.status = WebhookDeliveryStatus.PENDING;
        delivery.nextAttemptAt = Instant.now();
        return delivery;
    }

    public void delivered() {
        status = WebhookDeliveryStatus.DELIVERED;
        deliveredAt = Instant.now();
        attemptCount++;
        lastError = null;
    }

    public void failed(String error) {
        attemptCount++;
        lastError = error.substring(0, Math.min(error.length(), 500));
        if (attemptCount >= 8) {
            status = WebhookDeliveryStatus.FAILED;
        } else {
            status = WebhookDeliveryStatus.RETRY;
            nextAttemptAt = Instant.now().plusSeconds(Math.min(300, 1L << attemptCount));
        }
    }
}
