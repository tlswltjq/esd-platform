package com.stove.common.event;

/**
 * 이벤트 타입 상수. Kafka 헤더 {@code eventType} 및 Outbox 레코드에 기록되어
 * 컨슈머가 역직렬화 대상 클래스를 고르는 기준이 된다.
 */
public final class EventType {

    // studio
    public static final String GAME_REGISTERED = "GameRegistered";
    public static final String BUILD_UPLOADED = "BuildUploaded";

    // review
    public static final String REVIEW_APPROVED = "ReviewApproved";
    public static final String REVIEW_REJECTED = "ReviewRejected";

    // catalog
    public static final String PRODUCT_CHANGED = "ProductChanged";

    // order
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CANCELED = "OrderCanceled";

    // payment
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_CANCELLED = "PaymentCancelled";

    // license
    public static final String LICENSE_ISSUED = "LicenseIssued";
    public static final String LICENSE_REVOKED = "LicenseRevoked";
    public static final String LICENSE_ISSUE_FAILED = "LicenseIssueFailed";

    private EventType() {
    }
}
