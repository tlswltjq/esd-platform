package com.stove.catalog.core.domain;

import com.stove.common.event.payload.OrderLine;
import java.util.List;

/**
 * 서버가 확정한 가격. 클라이언트가 보낸 금액은 참고값일 뿐이며
 * 주문/결제 금액의 단일 진실 공급원은 이 값이다.
 *
 * @param lines 구매 시점의 상품·가격·정산 조건을 담은 항목 목록.
 * @param totalAmount 각 주문 항목의 실결제액을 합산한 총액.
 * @param currency 금액의 통화 코드.
 */
public record Quote(List<OrderLine> lines, long totalAmount, String currency) {
}
