package com.stove.order.api.application.port;

import com.stove.common.event.payload.OrderLine;
import java.util.List;

/** catalog 가 확정한 가격. 주문 금액의 단일 진실 공급원이다. */
public record CatalogQuote(List<OrderLine> lines, long totalAmount, String currency) {
}
