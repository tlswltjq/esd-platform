package com.stove.common.event;

/**
 * 이벤트 타입 상수. Kafka 헤더 {@code eventType} 및 Outbox 레코드에 기록되어
 * 컨슈머가 역직렬화 대상 클래스를 고르는 기준이 된다.
 */
public final class EventType {

    // auth
    public static final String USER_REGISTERED = "UserRegistered";

    // studio
    public static final String GAME_REGISTERED = "GameRegistered";
    public static final String BUILD_UPLOADED = "BuildUploaded";
    public static final String BUILD_VALIDATED = "BuildValidated";
    public static final String BUILD_VALIDATION_FAILED = "BuildValidationFailed";
    public static final String SUBMISSION_CREATED = "SubmissionCreated";
    public static final String RELEASE_SCHEDULED = "ReleaseScheduled";
    public static final String RELEASE_PUBLISHED = "ReleasePublished";
    public static final String RELEASE_ROLLED_BACK = "ReleaseRolledBack";

    // review
    public static final String REVIEW_APPROVED = "ReviewApproved";
    public static final String REVIEW_REJECTED = "ReviewRejected";
    public static final String SUBMISSION_REVIEW_APPROVED = "SubmissionReviewApproved";
    public static final String REVIEW_CHANGES_REQUESTED = "ReviewChangesRequested";
    public static final String REVIEW_APPEALED = "ReviewAppealed";

    // catalog
    public static final String PRODUCT_CHANGED = "ProductChanged";

    // order
    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CANCELED = "OrderCanceled";

    // payment
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_CANCELLED = "PaymentCancelled";
    public static final String PAYMENT_FAILED = "PaymentFailed";

    // license
    public static final String LICENSE_ISSUED = "LicenseIssued";
    public static final String LICENSE_REVOKED = "LicenseRevoked";
    public static final String LICENSE_ISSUE_FAILED = "LicenseIssueFailed";

    private EventType() {
    }
}
