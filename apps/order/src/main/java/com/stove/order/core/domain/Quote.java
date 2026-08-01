package com.stove.order.core.domain;

import com.stove.common.event.payload.OrderLine;
import java.util.List;

/**
 * catalog 가 확정한 가격. 주문 금액의 단일 진실 공급원이며,
 * 클라이언트가 보낸 금액은 이 값과 대조하는 용도로만 쓰인다.
 */
public record Quote(List<OrderLine> lines, long totalAmount, String currency) {
}
