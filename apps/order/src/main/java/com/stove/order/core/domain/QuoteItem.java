package com.stove.order.core.domain;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;

/**
 * catalog 에 가격 재계산을 요청할 항목.
 *
 * <p>검증을 여기에 두는 근거는 {@code catalog} 의 같은 이름 값 객체와 같다(D-009) —
 * 어댑터의 {@code @Min(1)} 은 HTTP 경로 하나만 지키고, 어댑터는 늘어난다.
 * 이쪽은 <b>보내는 쪽</b>이라 한 겹 더 앞선다: 잘못된 수량이 여기서 걸리면
 * catalog 왕복 없이 끝나고, 실패 지점도 "업스트림 장애"가 아니라 요청 오류로 남는다.
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
