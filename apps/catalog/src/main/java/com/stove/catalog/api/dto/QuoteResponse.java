package com.stove.catalog.api.dto;

import com.stove.common.event.payload.OrderLine;
import java.util.List;

/**
 * 서버가 확정한 가격. 클라이언트가 보낸 금액은 참고값일 뿐이며
 * 주문/결제 금액의 단일 진실 공급원은 이 응답이다.
 */
public record QuoteResponse(List<OrderLine> lines, long totalAmount, String currency) {
}
