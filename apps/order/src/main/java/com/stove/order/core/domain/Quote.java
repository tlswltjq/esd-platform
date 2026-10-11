package com.stove.order.core.domain;

import com.stove.common.event.payload.OrderLine;
import java.util.List;

/**
 * catalog 가 확정한 가격. 주문 금액의 단일 진실 공급원이며,
 * 클라이언트가 보낸 금액은 이 값과 대조하는 용도로만 쓰인다.
 *
 * @param lines 구매 시점의 상품·가격·정산 조건을 담은 항목 목록.
 * @param totalAmount 각 주문 항목의 실결제액을 합산한 총액.
 * @param currency 금액의 통화 코드.
 */
public record Quote(List<OrderLine> lines, long totalAmount, String currency) {
}
