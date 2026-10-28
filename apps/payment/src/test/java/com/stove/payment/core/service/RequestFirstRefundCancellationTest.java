package com.stove.payment.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.PgAuthenticationUnavailable;
import com.stove.payment.core.domain.PgPaymentNotFound;
import com.stove.payment.core.domain.PgCancellationNotFound;
import com.stove.payment.core.domain.PgPaymentLookupForbidden;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class RequestFirstRefundCancellationTest {
    private final RequestRefundCancellation cancellation = mock(RequestRefundCancellation.class);
    private final RequestFirstRefundCancellation first = new RequestFirstRefundCancellation(cancellation);

    @Test void supplierUncertaintyIsLeftToTheDurableLookupInsteadOfKafkaPostRetries() {
        doThrow(new PgUnavailable("supplier outcome unknown")).when(cancellation).executeFirst("attempt");
        assertThatCode(() -> first.execute("attempt")).doesNotThrowAnyException();
    }

    @Test void databaseFailuresStillFailTheKafkaDelivery() {
        doThrow(new TransientDataAccessResourceException("database down")).when(cancellation).executeFirst("attempt");
        assertThatThrownBy(() -> first.execute("attempt")).isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test void authenticationFailureLeavesKafkaRetriesOutOfTheRefundPostSchedule() {
        doThrow(new PgAuthenticationUnavailable()).when(cancellation).executeFirst("attempt");
        assertThatCode(() -> first.execute("attempt")).doesNotThrowAnyException();
    }

    @Test void missingPaymentLookupRemainsPendingWithoutUsingKafkaPostRetries() {
        doThrow(new PgPaymentNotFound()).when(cancellation).executeFirst("attempt");
        assertThatCode(() -> first.execute("attempt")).doesNotThrowAnyException();
    }

    @Test void missingCancellationTargetRemainsPendingWithoutUsingKafkaPostRetries() {
        doThrow(new PgCancellationNotFound()).when(cancellation).executeFirst("attempt");
        assertThatCode(() -> first.execute("attempt")).doesNotThrowAnyException();
    }

    @Test void forbiddenPaymentLookupRemainsPendingWithoutUsingKafkaPostRetries() {
        doThrow(new PgPaymentLookupForbidden()).when(cancellation).executeFirst("attempt");
        assertThatCode(() -> first.execute("attempt")).doesNotThrowAnyException();
    }
}
