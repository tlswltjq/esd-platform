package com.stove.common.event;

/**
 * 토픽 = 애그리거트 단위, 이름에 스키마 버전을 포함한다.
 * 메시지 키는 애그리거트 ID(주문번호·상품코드 등) → 같은 애그리거트의 이벤트 순서 보장.
 */
public final class Topics {

    /** studio: 게임 등록/빌드 업로드 */
    public static final String STUDIO = "stove.studio.v1";
    /** review: 등급분류 심의 결과 */
    public static final String REVIEW = "stove.review.v1";
    /** catalog: 상품 마스터 변경(노출 상태·가격) */
    public static final String CATALOG = "stove.catalog.v1";
    public static final String ORDER = "stove.order.v1";
    public static final String PAYMENT = "stove.payment.v1";
    public static final String LICENSE = "stove.license.v1";

    private Topics() {
    }
}
