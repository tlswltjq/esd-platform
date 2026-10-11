package com.stove.studio.core.domain.notification;

/**
 * 웹훅 등록 직후 구독 정보와 서명 비밀값을 전달하는 결과.
 *
 * @param subscription 등록한 빌드 웹훅 구독 정보.
 * @param signingSecret 웹훅 서명 검증을 위해 등록 결과로 전달하는 비밀값 원문.
 */
public record RegisteredBuildWebhook(BuildWebhookSubscription subscription, String signingSecret) {
}
