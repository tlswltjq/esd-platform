package com.stove.license.core.service;

import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.kafka.contract.payload.LicenseGrantedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.PaidOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrantLicenseTest {
    @Mock LicenseRepository licenses;
    @Mock ProcessedEventGuard inbox;
    @Mock EntityManager entityManager;
    @Mock OutboxRecorder outbox;
    @InjectMocks GrantLicense grant;

    @Test
    void aNewGrantPublishesTheStoredPurchaseAndLicenseIdentity() {
        PaidOrder input = paid();
        when(inbox.firstDelivery(input.eventId(), GrantLicense.CONSUMER_GROUP, "OrderPaid")).thenReturn(true);

        grant.execute(input);

        ArgumentCaptor<License> stored = ArgumentCaptor.forClass(License.class);
        verify(licenses).save(stored.capture());
        License license = stored.getValue();
        ArgumentCaptor<LicenseGrantedEvent> event = ArgumentCaptor.forClass(LicenseGrantedEvent.class);
        verify(outbox).record(eq("License"), eq(license.getLicenseId()), event.capture());
        LicenseGrantedEvent published = event.getValue();
        assertThat(published.eventId()).isNotBlank().isNotEqualTo(input.eventId());
        assertThat(published.occurredAt()).isEqualTo(license.getAcquiredAt());
        assertThat(published.licenseId()).isEqualTo(license.getLicenseId());
        assertThat(published.orderNo()).isEqualTo(input.orderNo());
        assertThat(published.buyerSubject()).isEqualTo(input.buyerSubject());
        assertThat(published.productId()).isEqualTo(input.productId());
        assertThat(published.productCode()).isEqualTo(input.productCode());
        assertThat(published.paymentId()).isEqualTo(input.paymentId());
        assertThat(published.eventType()).isEqualTo("LicenseGranted");
        assertThat(published.topic()).isEqualTo("stove.license.v1");
        assertThat(published.partitionKey()).isEqualTo(input.orderNo());
        verifyNoMoreInteractions(outbox);
    }
}
