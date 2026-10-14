package com.stove.common.kafka.contract;

/**
 * 토픽 = 애그리거트 단위, 이름에 스키마 버전을 포함한다.
 * 메시지 키는 애그리거트 ID(주문번호·상품코드 등) → 같은 애그리거트의 이벤트 순서 보장.
 */
public final class Topics {

    public static final String AUTH = "stove.auth.v1";

    /** studio: 출시 자료와 심사·출시 요청 */
    public static final String STUDIO = "stove.studio.v1";
    /** review: 등급분류 심의 결과 */
    public static final String REVIEW = "stove.review.v1";
    /** release: 외부 출시 업무가 보고하는 요청 처리 결과 */
    public static final String RELEASE = "stove.release.v1";
    /** catalog: 상품 마스터 변경(노출 상태·가격) */
    public static final String CATALOG = "stove.catalog.v1";
    public static final String ORDER = "stove.order.v1";
    public static final String PAYMENT = "stove.payment.v1";
    public static final String LICENSE = "stove.license.v1";

    private Topics() {
    }
}
