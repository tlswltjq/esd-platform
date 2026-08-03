package com.stove.order.api.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.OrderLine;
import com.stove.order.core.domain.Quote;
import com.stove.order.core.domain.QuoteItem;
import com.stove.order.core.port.CatalogPort;
import com.stove.order.core.service.OrderCommandService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 주문 생성의 <b>검증 게이트 1단계</b> — 서버 측 금액 재계산.
 *
 * <p>클라이언트가 보낸 {@code expectedAmount} 는 화면과 서버의 가격 불일치를 <b>감지</b>하는 데만 쓰고,
 * 주문 금액으로는 절대 쓰지 않는다. 이 구분이 무너지면 D-009 계열의 금액 조작이 성립한다.
 *
 * <p>그리고 <b>가격을 못 받았으면 주문을 만들지 않는다.</b>
 * catalog 가 죽었을 때 주문이 생기면 금액이 확정되지 않은 채로 결제로 넘어간다.
 */
class PlaceOrderFacadeTest {

    private final CatalogPort catalogPort = mock(CatalogPort.class);
    private final OrderCommandService orderCommandService = mock(OrderCommandService.class);
    private final PlaceOrderFacade facade = new PlaceOrderFacade(catalogPort, orderCommandService);

    private static final List<QuoteItem> ITEMS = List.of(new QuoteItem(1L, 2));
    private static final List<OrderLine> LINES =
            List.of(new OrderLine(1L, "게임 A", 1001L, 30_000L, 2));

    private void catalogQuotes(long totalAmount) {
        when(catalogPort.quote(any())).thenReturn(new Quote(LINES, totalAmount, "KRW"));
    }

    @Test
    @DisplayName("서버가 확정한 금액으로 주문이 만들어진다 — 클라이언트 금액은 쓰이지 않는다")
    void orderUsesServerConfirmedAmount() {
        catalogQuotes(60_000L);

        facade.place(42L, ITEMS, 60_000L);

        // 넘어가는 것은 catalog 가 준 lines/currency 다. expectedAmount 는 전달되지 않는다.
        verify(orderCommandService).createOrder(42L, "KRW", LINES);
    }

    @Test
    @DisplayName("클라이언트 금액이 서버 금액과 다르면 주문이 거절된다")
    void priceMismatchIsRejected() {
        catalogQuotes(60_000L);

        // 화면에 30,000 이 떠 있었는데 서버는 60,000 이라고 한다.
        // 어느 쪽이 맞든 사용자가 동의한 금액이 아니므로 진행하지 않는다.
        assertThatThrownBy(() -> facade.place(42L, ITEMS, 30_000L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRICE_MISMATCH);

        verifyNoInteractions(orderCommandService);
    }

    @Test
    @DisplayName("클라이언트가 금액을 안 보내면 대조 없이 서버 금액으로 진행한다")
    void missingExpectedAmountSkipsComparison() {
        catalogQuotes(60_000L);

        facade.place(42L, ITEMS, null);

        verify(orderCommandService).createOrder(42L, "KRW", LINES);
    }

    @Test
    @DisplayName("catalog 호출이 실패하면 주문을 만들지 않는다 — 가격 미확정 주문을 결제로 넘기지 않는다")
    void catalogFailureStopsOrderCreation() {
        when(catalogPort.quote(any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "상품 정보를 확인할 수 없습니다."));

        assertThatThrownBy(() -> facade.place(42L, ITEMS, 60_000L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UPSTREAM_UNAVAILABLE);

        verifyNoInteractions(orderCommandService);
    }

    @Test
    @DisplayName("금액 대조는 catalog 호출 뒤에 일어난다 — 재계산 없이는 판정할 수 없다")
    void quoteIsAlwaysFetchedBeforeComparison() {
        catalogQuotes(60_000L);

        assertThatThrownBy(() -> facade.place(42L, ITEMS, 1L))
                .isInstanceOf(BusinessException.class);

        // 클라이언트 금액이 뭐든 서버 재계산은 반드시 일어난다.
        verify(catalogPort).quote(ITEMS);
        verify(orderCommandService, org.mockito.Mockito.never())
                .createOrder(anyLong(), anyString(), any());
    }
}
