package com.stove.license.core.service;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.domain.SuspensionRequest;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplyLicenseSuspensionTest {
    private static final Instant NOW = Instant.parse("2026-10-26T01:00:00.123456Z");
    @Mock LicenseRepository licenses;
    @Mock LicenseSuspensionRepository suspensions;
    @Mock ProcessedEventGuard inbox;
    @Mock OutboxRecorder outbox;
    @Mock EntityManager entityManager;

    @Test
    void savesSuspensionEvidenceAndPublishesCompletionFromTheStoredLicense() {
        License license = License.grant(paid());
        PaidOrder original = license.paidOrder();
        SuspensionRequest input = request(license);
        when(licenses.lockByOrderNo(input.orderNo())).thenReturn(Optional.of(license));
        when(inbox.firstDelivery(input.eventId(), ApplyLicenseSuspension.CONSUMER_GROUP,
                "LicenseSuspensionRequested")).thenReturn(true);

        service().execute(input);

        assertThat(license.getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(license.paidOrder()).isEqualTo(original);
        ArgumentCaptor<LicenseSuspension> stored = ArgumentCaptor.forClass(LicenseSuspension.class);
        verify(suspensions).save(stored.capture());
        assertThat(stored.getValue().request()).isEqualTo(input);
        assertThat(stored.getValue().getSuspendedAt()).isEqualTo(NOW);
        ArgumentCaptor<LicenseSuspendedEvent> event = ArgumentCaptor.forClass(LicenseSuspendedEvent.class);
        verify(outbox).record(eq("License"), eq(license.getLicenseId()), event.capture());
        LicenseSuspendedEvent completed = event.getValue();
        assertThat(completed.eventId()).isEqualTo(stored.getValue().getCompletedEventId());
        assertThat(completed.occurredAt()).isEqualTo(NOW);
        assertThat(completed.requestEventId()).isEqualTo(input.eventId());
        assertThat(completed.refundId()).isEqualTo(input.refundId());
        assertThat(completed.licenseId()).isEqualTo(license.getLicenseId());
        assertThat(completed.orderNo()).isEqualTo(input.orderNo());
        assertThat(completed.buyerSubject()).isEqualTo(input.buyerSubject());
        assertThat(completed.productId()).isEqualTo(input.productId());
        assertThat(completed.productCode()).isEqualTo(input.productCode());
        assertThat(completed.paymentId()).isEqualTo(input.paymentId());
        assertThat(completed.topic()).isEqualTo("stove.license.v1");
        assertThat(completed.partitionKey()).isEqualTo(input.orderNo());
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void anIdenticalRequestDoesNotChangeTheStateOrPublishAnotherCompletion() {
        License license = License.grant(paid());
        SuspensionRequest input = request(license);
        license.suspend(input.eventId());
        LicenseSuspension previous = LicenseSuspension.record(license, input, NOW.minusSeconds(10));
        when(licenses.lockByOrderNo(input.orderNo())).thenReturn(Optional.of(license));
        when(suspensions.findByRequestEventId(input.eventId())).thenReturn(Optional.of(previous));

        service().execute(input);

        assertThat(previous.getSuspendedAt()).isEqualTo(NOW.minusSeconds(10));
        verifyNoInteractions(outbox, entityManager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer", "productId", "productCode", "paymentId"})
    void inconsistentPurchaseEvidenceDoesNotRecordAnything(String field) {
        License license = License.grant(paid());
        SuspensionRequest original = request(license);
        SuspensionRequest input = new SuspensionRequest(original.eventId(), original.occurredAt(), original.refundId(),
                original.orderNo(), field.equals("buyer") ? "other" : original.buyerSubject(),
                field.equals("productId") ? 99L : original.productId(),
                field.equals("productCode") ? "Other-1" : original.productCode(),
                field.equals("paymentId") ? id() : original.paymentId());
        when(licenses.lockByOrderNo(input.orderNo())).thenReturn(Optional.of(license));

        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        verifyNoInteractions(inbox, suspensions, outbox, entityManager);
    }

    @Test
    void noLicenseDoesNotMeanSuspensionCompleted() {
        SuspensionRequest input = request(License.grant(paid()));
        assertThatThrownBy(() -> service().execute(input)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inbox, suspensions, outbox, entityManager);
    }

    private ApplyLicenseSuspension service() {
        return new ApplyLicenseSuspension(licenses, suspensions, inbox, outbox, entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SuspensionRequest request(License license) {
        return new SuspensionRequest(id(), NOW.minusSeconds(60), id(), license.getOrderNo(), license.getBuyerSubject(),
                license.getProductId(), license.getProductCode(), license.getPaymentId());
    }
}
