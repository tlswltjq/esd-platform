package com.stove.license;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.LicenseRestorationRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.domain.SuspensionRequest;
import com.stove.license.core.domain.RestorationRequest;
import com.stove.license.core.service.GrantLicense;
import com.stove.license.core.service.ApplyLicenseSuspension;
import com.stove.license.core.service.ApplyLicenseRestoration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LicenseRestorationIntegrationTest extends LicenseIntegrationTestBase {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    @Autowired GrantLicense grant;
    @Autowired ApplyLicenseSuspension suspend;
    @Autowired ApplyLicenseRestoration restore;
    @Autowired LicenseRepository licenses;
    @Autowired LicenseSuspensionRepository suspensions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;

    @BeforeEach void initialize() { clear(); when(clock.instant()).thenReturn(NOW); }
    @AfterEach void clear() {
        listeners.stop(); reset(outbox);
        jdbc.update("DELETE FROM license_approval_correction"); jdbc.update("DELETE FROM license_restoration"); jdbc.update("DELETE FROM license_suspension"); jdbc.update("DELETE FROM license_purchase");
        jdbc.update("DELETE FROM product_license"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }
    @Test void actualRestorationPreservesPurchaseAndOldSuspensionOrRestorationReplaysCannotUndoANewerSuspension() {
        RestorationRequest input = fixture(); PaidOrder purchase = stored(input).paidOrder(); restore.execute(input);
        assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.ACTIVE); assertThat(stored(input).paidOrder()).isEqualTo(purchase);
        restore.execute(input);
        LicenseSuspension original = suspensions.findByRequestEventId(input.suspensionRequestEventId()).orElseThrow();
        suspend.execute(original.request()); assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        SuspensionRequest next = new SuspensionRequest(id(), NOW.plusSeconds(1), id(), input.orderNo(), input.buyerSubject(), input.productId(), input.productCode(), input.paymentId());
        suspend.execute(next); restore.execute(input);
        assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(stored(input).getSuspensionRequestEventId()).isEqualTo(next.eventId()); assertThat(completions()).isEqualTo(1);
    }
    @Test void anotherSuspensionCannotBeRestoredAndFailedValidationHasNoInboxOrCompletion() {
        RestorationRequest input = fixture();
        RestorationRequest other = new RestorationRequest(input.eventId(), input.occurredAt(), input.refundId(), input.attemptId(), input.failureEventId(),
                input.licenseId(), input.orderNo(), input.buyerSubject(), input.productId(), input.productCode(), input.paymentId(), id(), input.suspensionEventId());
        assertThatThrownBy(() -> restore.execute(other)).isInstanceOf(RuntimeException.class);
        assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.SUSPENDED); assertThat(completions()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='license-restoration'", Integer.class)).isZero();
    }
    @Test void actualStateHistoryInboxAndOutboxRollbackTogetherAndReplayCanResume() {
        RestorationRequest input = fixture();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); licenses.flush(); throw new IllegalStateException("after restored state/history/outbox flush");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThatThrownBy(() -> restore.execute(input)).isInstanceOf(IllegalStateException.class);
        assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.SUSPENDED); assertThat(completions()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM license_restoration", Integer.class)).isZero();
        reset(outbox); restore.execute(input); assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.ACTIVE); assertThat(completions()).isEqualTo(1);
    }
    @Test void concurrentDeliveriesRestoreOnceAndKeepOneCompletion() throws Exception {
        RestorationRequest input = fixture(); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> work = List.of(pool.submit(() -> { start.await(); restore.execute(input); return null; }),
                    pool.submit(() -> { start.await(); restore.execute(input); return null; }));
            start.countDown(); for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
            assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.ACTIVE); assertThat(completions()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void realKafkaDeliversTheCorrelatedRestorationRequestAndDuplicateOnlyProducesOneResult() throws Exception {
        RestorationRequest input = fixture();
        LicenseRestorationRequestedEvent event = new LicenseRestorationRequestedEvent(input.eventId(), input.occurredAt(), input.refundId(), input.attemptId(),
                input.failureEventId(), input.licenseId(), input.orderNo(), input.buyerSubject(), input.productId(), input.productCode(), input.paymentId(),
                input.suspensionRequestEventId(), input.suspensionEventId());
        listeners.start();
        for (int index = 0; index < 2; index++) {
            ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.partitionKey(), mapper.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8)); kafka.send(record).get(10, TimeUnit.SECONDS);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(stored(input).getStatus()).isEqualTo(LicenseStatus.ACTIVE); assertThat(completions()).isEqualTo(1);
        });
    }
    private RestorationRequest fixture() {
        PaidOrder purchase = paid(); grant.execute(purchase); License license = licenses.findByOrderNo(purchase.orderNo()).orElseThrow();
        SuspensionRequest request = new SuspensionRequest(id(), NOW.minusSeconds(10), id(), license.getOrderNo(), license.getBuyerSubject(),
                license.getProductId(), license.getProductCode(), license.getPaymentId());
        suspend.execute(request); LicenseSuspension suspended = suspensions.findByRequestEventId(request.eventId()).orElseThrow();
        return new RestorationRequest(id(), NOW, request.refundId(), id(), id(), license.getLicenseId(), license.getOrderNo(), license.getBuyerSubject(),
                license.getProductId(), license.getProductCode(), license.getPaymentId(), request.eventId(), suspended.getCompletedEventId());
    }
    private License stored(RestorationRequest input) { return licenses.findByOrderNo(input.orderNo()).orElseThrow(); }
    private int completions() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseRestored'", Integer.class); }
}
