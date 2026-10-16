package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import com.stove.common.web.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.stream.Stream;

class OrderTest {
    @Test void resultIdentityIsRequiredEvenWithoutAnEventListener() {
        var o = order(); var r = result(completed(o));
        assertThatThrownBy(() -> new PaymentResult(null, r.occurredAt(), r.orderNo(), r.requestEventId(),
                r.paymentId(), r.buyerSubject(), r.amount(), r.currency(), OrderStatus.PAID, null, null))
                .isInstanceOf(BusinessException.class);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(o.paymentResult()).isNull();
    }

    @Test void resultMustBeFinalAndFailureMustExplainItsReason() {
        var r = result(completed(order()));
        for (var status : new OrderStatus[]{null, OrderStatus.PENDING, OrderStatus.FAILED})
            assertThatThrownBy(() -> new PaymentResult(r.eventId(), r.occurredAt(), r.orderNo(), r.requestEventId(),
                    r.paymentId(), r.buyerSubject(), r.amount(), r.currency(), status, null, null))
                    .isInstanceOf(BusinessException.class);
    }

    @Test void storesOriginalProductAndSeparateCustomerAmounts() {
        var o = order();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(o.getTitle()).isEqualTo("한글 게임");
        assertThat(o.getListAmount()).isEqualTo(10000);
        assertThat(o.getDiscountAmount()).isZero();
        assertThat(o.getAmount()).isEqualTo(o.getListAmount());
        assertThat(o.getCreatedAt().getNano() % 1000).isZero();
        assertThat(o.paymentResult()).isNull();
    }
    @Test void rejectsFreeProductsAndChangedPublicTerms() {
        assertThatThrownBy(() -> Order.create("usr_buyer", request(), new PublishedProduct(1L, "Game-1", "Free", 3, 0, "KRW"), id()))
                .isInstanceOf(BusinessException.class);
        for (var p : new PublishedProduct[]{new PublishedProduct(1L, "Game-1", "Changed", 4, 10000, "KRW"),
                new PublishedProduct(1L, "Game-1", "Changed", 3, 11000, "KRW")})
            assertThatThrownBy(() -> Order.create("usr_buyer", request(), p, id())).isInstanceOf(BusinessException.class);
    }
    @Test void sameRequestMustKeepCodeVersionAndAmount() {
        var r = request(); var o = Order.create("usr_buyer", r, product(), id());
        o.requireSameRequest(r);
        for (var other : new OrderRequest[]{new OrderRequest(r.requestId(), "game-1", 3, 10000),
                new OrderRequest(r.requestId(), r.productCode(), 4, 10000),
                new OrderRequest(r.requestId(), r.productCode(), 3, 9999)})
            assertThatThrownBy(() -> o.requireSameRequest(other)).isInstanceOf(BusinessException.class);
    }
    @Test void finalResultIsImmutableAndRepeatedDeliveryDoesNotChangeTimestamp() {
        var o = order(); var e = completed(o); var r = result(e);
        o.apply(r); var updated = o.getUpdatedAt();
        o.apply(r);
        assertThat(o.getUpdatedAt()).isEqualTo(updated);
        assertThat(o.paymentResult()).isEqualTo(r);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(() -> o.apply(result(completed(o)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> o.apply(new PaymentResult(id(), r.occurredAt(), r.orderNo(), r.requestEventId(),
                r.paymentId(), r.buyerSubject(), r.amount(), r.currency(), OrderStatus.FAILED, "DECLINED", "Failed")))
                .isInstanceOf(BusinessException.class);
        assertThat(o.getAmount()).isEqualTo(10000);
    }
    @Test void rejectsFeeDeductedAmountAndWrongPaymentConnection() {
        var o = order(); var r = result(completed(o));
        for (var wrong : new PaymentResult[]{
                new PaymentResult(r.eventId(), r.occurredAt(), r.orderNo(), "another-request", r.paymentId(), r.buyerSubject(), r.amount(), "KRW", OrderStatus.PAID, null, null),
                new PaymentResult(r.eventId(), r.occurredAt(), r.orderNo(), r.requestEventId(), r.paymentId(), "other-buyer", r.amount(), "KRW", OrderStatus.PAID, null, null),
                new PaymentResult(r.eventId(), r.occurredAt(), r.orderNo(), r.requestEventId(), r.paymentId(), r.buyerSubject(), 9800, "KRW", OrderStatus.PAID, null, null)})
            assertThatThrownBy(() -> o.apply(wrong)).isInstanceOf(BusinessException.class);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
    }
    static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of(null, "Game-1", 3, 10000), Arguments.of("bad", "Game-1", 3, 10000),
                Arguments.of(id(), "bad/code", 3, 10000), Arguments.of(id(), "Game-1", 0, 10000),
                Arguments.of(id(), "Game-1", 3, -1));
    }
    @ParameterizedTest @MethodSource("invalidRequests")
    void rejectsInvalidRequest(String requestId, String code, long version, long amount) {
        assertThatThrownBy(() -> new OrderRequest(requestId, code, version, amount)).isInstanceOf(BusinessException.class);
    }
    @Test void priceCompositionCannotHideFeesOrNegativeDiscounts() {
        assertThatThrownBy(() -> new OrderPrice(10000, -1, 10001, "KRW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPrice(10000, 0, 9800, "KRW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPrice(10000, 0, 10000, "USD")).isInstanceOf(IllegalArgumentException.class);
    }
}
