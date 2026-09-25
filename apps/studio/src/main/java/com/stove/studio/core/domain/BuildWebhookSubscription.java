package com.stove.studio.core.domain;

import com.stove.common.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "build_webhook_subscription")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildWebhookSubscription extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long gameId;
    @Column(nullable = false) private Long workspaceId;
    @Column(nullable = false, length = 500) private String endpointUrl;
    @Column(nullable = false, length = 1000) private String encryptedSecret;
    @Column(nullable = false) private boolean active;

    public static BuildWebhookSubscription create(Long gameId, Long workspaceId,
                                                   String endpointUrl, String encryptedSecret) {
        BuildWebhookSubscription subscription = new BuildWebhookSubscription();
        subscription.gameId = gameId;
        subscription.workspaceId = workspaceId;
        subscription.endpointUrl = endpointUrl;
        subscription.encryptedSecret = encryptedSecret;
        subscription.active = true;
        return subscription;
    }

    public void disable() {
        active = false;
    }
}
