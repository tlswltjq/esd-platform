package com.stove.order.core.domain;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.product;
import static com.stove.order.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OrderSaleTermsTest {
    @Test void aHiddenOnSaleProductCannotCreateANewOrder() {
        PublishedProduct p = product();
        PublishedProduct hidden = new PublishedProduct(p.productId(), p.productCode(), p.title(), p.contentVersion(),
                p.price(), p.currency(), p.saleStatus(), false);
        assertThatThrownBy(() -> Order.create("buyer", request(), hidden, id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode().statusCode()).isEqualTo(404));
    }

    @Test void aStoppedProductCannotCreateANewOrder() {
        PublishedProduct original = product();
        PublishedProduct stopped = new PublishedProduct(original.productId(), original.productCode(), original.title(),
                original.contentVersion(), original.price(), original.currency(), "STOPPED");
        assertThatThrownBy(() -> Order.create("buyer", request(), stopped, id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(OrderErrorCode.PRODUCT_NOT_ON_SALE));
    }

    @Test void anExistingOrderAdmitsAtItsOriginalPriceAndDeadlineWithoutRepricingTheProduct() {
        Order order = Order.create("buyer", request(), product(), id());
        PaymentAdmissionInput admission = new PaymentAdmissionInput(id(), order.getOrderNo(), order.getRequestEventId(),
                id(), order.getBuyerSubject(), order.getAmount(), order.getCurrency());
        assertThat(order.beginPaymentProcessing(admission, order.getCreatedAt().plus(Duration.ofMinutes(29)))).isTrue();
        assertThat(order.getAmount()).isEqualTo(product().price());
        assertThat(order.getExpiresAt()).isEqualTo(order.getCreatedAt().plus(Duration.ofMinutes(30)));
        assertThat(order.expire(order.getExpiresAt().plusSeconds(1))).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
    }
}
