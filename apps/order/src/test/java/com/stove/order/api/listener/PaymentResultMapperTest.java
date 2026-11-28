package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.message;
import static com.stove.order.OrderFixtures.failed;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.contract.EventMessage;
import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.contract.payload.OrderPaymentFailedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentResultMapperTest {
    static Stream<DomainEvent> outcomes() {
        Order order = order();
        return Stream.of(completed(order), failed(order));
    }

    private PaymentResult convert(EventMessage message, DomainEvent event) {
        return switch (event) {
            case OrderPaymentCompletedEvent completed -> PaymentResultMapper.from(message, completed);
            case OrderPaymentFailedEvent failed -> PaymentResultMapper.from(message, failed);
            default -> throw new IllegalArgumentException();
        };
    }

    @ParameterizedTest @MethodSource("outcomes")
    void rejectsMismatchedHeaderForEitherOutcome(DomainEvent event) {
        for (EventMessage message : new EventMessage[]{null,
                new EventMessage("other", event.eventType(), event.partitionKey(), ""),
                new EventMessage(event.eventId(), "other", event.partitionKey(), ""),
                new EventMessage(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> convert(message, event)).isInstanceOf(BusinessException.class);
    }

    @Test
    void translatesBothOutcomesWithoutLosingFailureInformation() {
        Order order = order();
        OrderPaymentCompletedEvent success = completed(order);
        assertThat(PaymentResultMapper.from(message(success), success).status()).isEqualTo(OrderStatus.PAID);
        OrderPaymentFailedEvent failure = failed(order);
        PaymentResult result = PaymentResultMapper.from(message(failure), failure);
        assertThat(result.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("DECLINED");
        assertThat(result.reason()).isEqualTo(failure.reason());
    }
}
