package com.stove.common.event.payload;

/**
 * 이벤트에 실리는 주문 항목 스냅샷. 금액은 최소 화폐 단위(KRW=원)의 정수.
 *
 * <p>{@code sellerId} 는 정산 배분의 기준 키다. catalog → order → payment → settlement 로
 * 그대로 전파되므로, 이 레코드에 필드를 추가하면 커머스 트랙 전체가 영향을 받는다.
 */
public record OrderLine(Long productId, String productName, Long sellerId, long unitPrice, int quantity) {

    public long lineAmount() {
        return unitPrice * quantity;
    }
}
