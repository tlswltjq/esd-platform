package com.stove.common.kafka.contract;

/**
 * 이벤트 타입 상수. Kafka 헤더 {@code eventType} 및 Outbox 레코드에 기록되어
 * 컨슈머가 역직렬화 대상 클래스를 고르는 기준이 된다.
 */
public final class EventType {

    // auth
    /** 계정 가입 완료를 나타내는 이벤트 타입. */
    public static final String USER_REGISTERED = "UserRegistered";

    // studio
    /** 게임 프로젝트 등록를 나타내는 이벤트 타입. */
    public static final String GAME_REGISTERED = "GameRegistered";
    /** 빌드 업로드 완료를 나타내는 이벤트 타입. */
    public static final String BUILD_UPLOADED = "BuildUploaded";
    /** 빌드 검증 성공를 나타내는 이벤트 타입. */
    public static final String BUILD_VALIDATED = "BuildValidated";
    /** 빌드 검증 실패를 나타내는 이벤트 타입. */
    public static final String BUILD_VALIDATION_FAILED = "BuildValidationFailed";
    /** 심사 제출물 생성를 나타내는 이벤트 타입. */
    public static final String SUBMISSION_CREATED = "SubmissionCreated";
    /** 릴리스 공개 예약를 나타내는 이벤트 타입. */
    public static final String RELEASE_SCHEDULED = "ReleaseScheduled";
    /** 릴리스 공개 완료를 나타내는 이벤트 타입. */
    public static final String RELEASE_PUBLISHED = "ReleasePublished";
    /** 이전 릴리스로 롤백를 나타내는 이벤트 타입. */
    public static final String RELEASE_ROLLED_BACK = "ReleaseRolledBack";

    // review
    /** 프로젝트 단위 심사 승인를 나타내는 이벤트 타입. */
    public static final String REVIEW_APPROVED = "ReviewApproved";
    /** 프로젝트 단위 심사 반려를 나타내는 이벤트 타입. */
    public static final String REVIEW_REJECTED = "ReviewRejected";
    /** 제출물의 유형별 심사 승인를 나타내는 이벤트 타입. */
    public static final String SUBMISSION_REVIEW_APPROVED = "SubmissionReviewApproved";
    /** 심사 자료 수정 요청를 나타내는 이벤트 타입. */
    public static final String REVIEW_CHANGES_REQUESTED = "ReviewChangesRequested";
    /** 심사 결과 재검토 요청를 나타내는 이벤트 타입. */
    public static final String REVIEW_APPEALED = "ReviewAppealed";

    // catalog
    public static final String CATALOG_PRODUCT_PUBLISHED = "CatalogProductPublished";
    /** 판매 상품 정보 변경를 나타내는 이벤트 타입. */
    public static final String PRODUCT_CHANGED = "ProductChanged";

    // order
    public static final String ORDER_PAID = "OrderPaid";
    /** Order에 환불을 위한 보유권 정지 요청이 등록됐음을 나타낸다. */
    public static final String LICENSE_SUSPENSION_REQUESTED = "LicenseSuspensionRequested";
    public static final String REFUND_EXECUTION_REQUESTED = "RefundExecutionRequested";
    public static final String ORDER_PLACED = "OrderPlaced";
    public static final String ORDER_PAYMENT_COMPLETED = "OrderPaymentCompleted";
    public static final String ORDER_PAYMENT_FAILED = "OrderPaymentFailed";
    /** 주문 생성를 나타내는 이벤트 타입. */
    public static final String ORDER_CREATED = "OrderCreated";
    /** 주문 취소를 나타내는 이벤트 타입. */
    public static final String ORDER_CANCELED = "OrderCanceled";

    // payment
    public static final String REFUND_PAYMENT_CONFIRMED = "RefundPaymentConfirmed";
    public static final String REFUND_EXECUTION_SUCCEEDED = "RefundExecutionSucceeded";
    /** 결제 승인 완료를 나타내는 이벤트 타입. */
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    /** 결제 환불 완료를 나타내는 이벤트 타입. */
    public static final String PAYMENT_CANCELLED = "PaymentCancelled";
    /** 결제 승인 실패를 나타내는 이벤트 타입. */
    public static final String PAYMENT_FAILED = "PaymentFailed";

    // license
    public static final String LICENSE_GRANTED = "LicenseGranted";
    public static final String LICENSE_SUSPENDED = "LicenseSuspended";
    /** 이용권 지급 완료를 나타내는 이벤트 타입. */
    public static final String LICENSE_ISSUED = "LicenseIssued";
    /** 이용권 회수 완료를 나타내는 이벤트 타입. */
    public static final String LICENSE_REVOKED = "LicenseRevoked";
    /** 이용권 지급 실패를 나타내는 이벤트 타입. */
    public static final String LICENSE_ISSUE_FAILED = "LicenseIssueFailed";

    public static final String REFUND_EXECUTION_FAILED = "RefundExecutionFailed";
    public static final String LICENSE_RESTORATION_REQUESTED = "LicenseRestorationRequested";
    public static final String LICENSE_RESTORED = "LicenseRestored";

    private EventType() {
    }
}
