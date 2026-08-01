package com.stove.order.application;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.order.api.dto.CreateOrderRequest;
import com.stove.order.api.dto.OrderResponse;
import com.stove.order.infrastructure.client.CatalogClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 주문 생성 유스케이스 오케스트레이션.
 *
 * <p><b>검증 게이트 1단계 — 서버 측 금액 재계산.</b>
 * 클라이언트가 보낸 expectedAmount 는 화면-서버 간 가격 불일치를 감지하는 용도로만 쓰고,
 * 실제 주문 금액은 catalog 가 확정한 값만 사용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderService {

    private final CatalogClient catalogClient;
    private final OrderCommandService orderCommandService;

    public OrderResponse place(CreateOrderRequest request) {
        List<CatalogClient.QuoteItem> quoteItems = request.items().stream()
                .map(i -> new CatalogClient.QuoteItem(i.productId(), i.quantity()))
                .toList();

        CatalogClient.QuoteResult quote = catalogClient.quote(quoteItems);

        if (request.expectedAmount() != null && request.expectedAmount() != quote.totalAmount()) {
            log.warn("주문 금액 불일치 expected={} actual={}", request.expectedAmount(), quote.totalAmount());
            throw new BusinessException(ErrorCode.PRICE_MISMATCH);
        }

        return OrderResponse.from(
                orderCommandService.createOrder(request.memberId(), quote.currency(), quote.lines()));
    }
}
