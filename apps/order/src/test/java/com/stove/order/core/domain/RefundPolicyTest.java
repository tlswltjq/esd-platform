package com.stove.order.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RefundPolicyTest {
    private static final Instant PAYMENT_CONFIRMED_AT = Instant.parse("2026-10-25T10:00:00.123456Z");

    private final RefundPolicy policy = new RefundPolicy();

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "결제 확정과 동시에 접수 | 2026-10-25T10:00:00.123456Z | true",
            "2시간 뒤 접수 | 2026-10-25T12:00:00.123456Z | true",
            "4시간보다 1마이크로초 앞서 접수 | 2026-10-25T14:00:00.123455Z | true",
            "정확히 4시간에 접수 | 2026-10-25T14:00:00.123456Z | true",
            "4시간보다 1마이크로초 늦게 접수 | 2026-10-25T14:00:00.123457Z | false",
            "4시간을 초과했지만 같은 초에 접수 | 2026-10-25T14:00:00.999999Z | false",
            "4시간 59분 59초 뒤 접수 | 2026-10-25T14:59:59.123456Z | false",
            "다음 날 접수 | 2026-10-26T10:00:00.123456Z | false"
    }, delimiter = '|')
    void classifiesRecordedReceiptAgainstTheFourHourDeadline(
            String scenario, String receivedAtText, boolean expected) {
        Instant receivedAt = Instant.parse(receivedAtText);

        assertThat(policy.isWithinAutomaticRefundWindow(PAYMENT_CONFIRMED_AT, receivedAt))
                .as(scenario)
                .isEqualTo(expected);
    }

    @Test
    void allowsAnOnTimeReceiptEvenWhenItsRecordedDatesAreLongPast() {
        Instant paymentConfirmedAt = Instant.parse("2000-01-01T10:00:00.123456Z");
        Instant receivedAt = Instant.parse("2000-01-01T14:00:00.123456Z");

        assertThat(policy.isWithinAutomaticRefundWindow(paymentConfirmedAt, receivedAt)).isTrue();
    }

    @Test
    void requiresReviewForALateReceiptEvenWhenItsRecordedDatesAreInTheFuture() {
        Instant paymentConfirmedAt = Instant.parse("2099-01-01T10:00:00.123456Z");
        Instant receivedAt = Instant.parse("2099-01-01T14:00:00.123457Z");

        assertThat(policy.isWithinAutomaticRefundWindow(paymentConfirmedAt, receivedAt)).isFalse();
    }
}
