package com.stove.studio.core.domain.notification;

/**
 * 빌드 결과 웹훅 발송의 대기·재시도·종료 상태.
 */
public enum WebhookDeliveryStatus {
    /** 웹훅의 최초 발송을 기다리는 상태. */
    PENDING,
    /** 실패 후 다음 발송을 기다리는 상태. */
    RETRY,
    /** 웹훅을 수신처에 전달한 상태. */
    DELIVERED,
    /** 웹훅 발송 시도 한도를 소진한 최종 실패 상태. */
    FAILED
}
