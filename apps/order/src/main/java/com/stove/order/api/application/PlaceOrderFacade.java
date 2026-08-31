package com.stove.order.api.application;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.Quote;
import com.stove.order.core.domain.QuoteItem;
import com.stove.order.core.port.CatalogPort;
import com.stove.order.core.service.OrderCommandService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 주문 생성 오케스트레이션. <b>트랜잭션을 열지 않는다</b>(동기 HTTP 호출이 들어오면 안 된다).
 * 검증 게이트 1단계 — 금액은 catalog 가 확정한 값만 쓴다. docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderFacade {

    private final CatalogPort catalogPort;
    private final OrderCommandService orderCommandService;

    public Order place(Long memberId, List<QuoteItem> items, Long expectedAmount) {
        Quote quote = catalogPort.quote(items);

        if (expectedAmount != null && expectedAmount != quote.totalAmount()) {
            log.warn("주문 금액 불일치 expected={} actual={}", expectedAmount, quote.totalAmount());
            throw new BusinessException(ErrorCode.PRICE_MISMATCH);
        }

        return orderCommandService.createOrder(memberId, quote.currency(), quote.lines());
    }
}
