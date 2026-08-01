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
 * 주문 생성 유스케이스 오케스트레이션.
 *
 * <p>이 모듈에서 조율이 필요한 유일한 경로다 — 외부 도메인(catalog) 포트와
 * 자기 도메인 서비스를 순서대로 부른다. 트랜잭션은 열지 않는다:
 * 동기 HTTP 호출이 쓰기 트랜잭션 안으로 들어오면 안 되기 때문이다.
 *
 * <p><b>검증 게이트 1단계 — 서버 측 금액 재계산.</b>
 * 클라이언트가 보낸 expectedAmount 는 화면-서버 간 가격 불일치를 감지하는 용도로만 쓰고,
 * 실제 주문 금액은 catalog 가 확정한 값만 사용한다.
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
