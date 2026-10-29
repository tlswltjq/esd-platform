package com.stove.payment.core.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PgPaymentAmountsTest {
    @Test void customerPaymentAndSupplierDiscountMustAddUpToTheOrderTotal() {
        new PgPaymentAmounts(8976, 24).requireTotal(9000);
        new PgPaymentAmounts(0, 9000).requireTotal(9000);
        assertThatThrownBy(() -> new PgPaymentAmounts(8975, 24).requireTotal(9000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PgPaymentAmounts(Long.MAX_VALUE, 24).requireTotal(9000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void negativeAmountsAndNonpositiveTotalsAreNotConfirmedSupplierFacts() {
        assertThatThrownBy(() -> new PgPaymentAmounts(-1, 24)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PgPaymentAmounts(9000, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PgPaymentAmounts(0, 0).requireTotal(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
