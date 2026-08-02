package com.stove.catalog.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;

/**
 * 가격 재계산 대상 한 줄. HTTP 요청 형식과 무관한 core 입력 모델이다.
 *
 * <p>수량 검증을 여기에 둔 이유 — 이 값은 <b>주문 금액의 출발점</b>이다.
 * 음수 수량이 통과하면 {@code lineAmount()} 가 음수가 되어 총액을 임의로 낮출 수 있고,
 * 그 총액이 PG 사전등록·콜백 대조의 기준값이 되므로 뒤따르는 검증 게이트가 전부
 * 조작된 금액 위에서 통과한다.
 *
 * <p>같은 검증이 {@code QuoteRequest.Item} 에도 {@code @Min(1)} 로 있지만 그것은 HTTP 어댑터의 몫이다.
 * 어댑터는 늘어날 수 있고 도메인 규칙은 도메인이 지켜야 한다 —
 * 값 객체가 애초에 잘못된 상태로 존재하지 못하게 막는다.
 */
public record QuoteItem(Long productId, int quantity) {

    public QuoteItem {
        if (productId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 ID가 없습니다.");
        }
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "수량은 1 이상이어야 합니다: quantity=" + quantity);
        }
    }
}
