package com.stove.studio.core.domain.notification;

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

/**
 * 프로젝트의 빌드 결과를 수신할 주소와 서명 설정.
 */
@Entity
@Getter
@Table(name = "build_webhook_subscription")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildWebhookSubscription extends BaseTimeEntity {
    /** 저장된 대상을 구별하는 식별자. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** Studio 게임 프로젝트 식별자. */
    @Column(nullable = false) private Long gameId;
    /** 프로젝트와 권한을 소유한 작업 공간 식별자. */
    @Column(nullable = false) private Long workspaceId;
    /** 빌드 결과를 통보할 HTTPS 수신 주소. */
    @Column(nullable = false, length = 500) private String endpointUrl;
    /** 웹훅 서명용 비밀값을 암호화한 문자열. */
    @Column(nullable = false, length = 1000) private String encryptedSecret;
    /** 현재 유효하여 사용할 수 있는지 여부. */
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
