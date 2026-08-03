package com.stove.order.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * catalog 에 보낼 재계산 항목.
 *
 * <p>D-009 는 catalog 쪽 같은 이름의 값 객체에서 수량 검증이 빠져 있던 결함이었고,
 * 수정하면서 <b>도메인 규칙은 어댑터가 아니라 도메인이 지킨다</b>는 근거를 그 클래스 javadoc 에 남겼다.
 * order 쪽 값 객체는 그때 같이 보지 않아 같은 모양으로 남아 있다 — 여기서 그것을 확인한다.
 */
class QuoteItemTest {

    @Test
    @DisplayName("정상 항목은 그대로 만들어진다")
    void validItemIsAccepted() {
        QuoteItem item = new QuoteItem(1L, 2);

        assertThat(item.productId()).isEqualTo(1L);
        assertThat(item.quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("[D-019] 수량 0 은 도메인이 거절한다")
    void zeroQuantityIsRejectedByTheDomain() {
        // 수정 전에는 가드 없는 빈 레코드라 그대로 만들어졌다.
        // HTTP 어댑터의 @Min(1) 만이 유일한 방어선이어서, 어댑터가 하나 더 늘거나
        // 내부 호출 경로가 생기면 조작된 수량이 그대로 catalog 재계산에 들어갔다.
        assertThatThrownBy(() -> new QuoteItem(1L, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("[D-019] 음수 수량은 도메인이 거절한다 — 총액을 임의로 낮출 수 있다")
    void negativeQuantityIsRejectedByTheDomain() {
        // 음수 수량이 통과하면 lineAmount() 가 음수가 되어 주문 총액을 낮출 수 있고,
        // 그 총액이 PG 사전등록·콜백 대조의 기준값이 되므로 뒤따르는 게이트가 전부
        // 조작된 금액 위에서 통과한다 (D-009 와 같은 구조).
        assertThatThrownBy(() -> new QuoteItem(1L, -5))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("[D-019] 상품 ID 없는 항목은 도메인이 거절한다")
    void missingProductIdIsRejectedByTheDomain() {
        assertThatThrownBy(() -> new QuoteItem(null, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
