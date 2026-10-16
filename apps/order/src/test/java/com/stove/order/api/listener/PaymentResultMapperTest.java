package com.stove.order.api.listener;

import static com.stove.order.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentResultMapperTest {
    static Stream<DomainEvent> outcomes() {
        Order order = order();
        return Stream.of(completed(order), failed(order));
    }

    private PaymentResult convert(EventEnvelope envelope, DomainEvent event) {
        return switch (event) {
            case OrderPaymentCompletedEvent completed -> PaymentResultMapper.from(envelope, completed);
            case OrderPaymentFailedEvent failed -> PaymentResultMapper.from(envelope, failed);
            default -> throw new IllegalArgumentException();
        };
    }

    @ParameterizedTest @MethodSource("outcomes")
    void rejectsMismatchedEnvelopeForEitherOutcome(DomainEvent event) {
        for (EventEnvelope envelope : new EventEnvelope[]{null,
                new EventEnvelope("other", event.eventType(), event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), "other", event.partitionKey(), ""),
                new EventEnvelope(event.eventId(), event.eventType(), "other", "")})
            assertThatThrownBy(() -> convert(envelope, event)).isInstanceOf(BusinessException.class);
    }

    @Test
    void translatesBothOutcomesWithoutLosingFailureInformation() {
        Order order = order();
        OrderPaymentCompletedEvent success = completed(order);
        assertThat(PaymentResultMapper.from(envelope(success), success).status()).isEqualTo(OrderStatus.PAID);
        OrderPaymentFailedEvent failure = failed(order);
        PaymentResult result = PaymentResultMapper.from(envelope(failure), failure);
        assertThat(result.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("DECLINED");
        assertThat(result.reason()).isEqualTo(failure.reason());
    }
}
