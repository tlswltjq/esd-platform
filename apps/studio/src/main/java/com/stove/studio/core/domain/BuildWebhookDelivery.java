package com.stove.studio.core.domain;

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

@Entity
@Getter
@Table(name = "build_webhook_delivery")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildWebhookDelivery extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long subscriptionId;
    @Column(nullable = false) private Long buildId;
    @Column(nullable = false, length = 50) private String eventType;
    @Lob @Column(nullable = false, columnDefinition = "TEXT") private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private WebhookDeliveryStatus status;
    @Column(nullable = false) private int attemptCount;
    @Column(nullable = false) private Instant nextAttemptAt;
    private Instant deliveredAt;
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
