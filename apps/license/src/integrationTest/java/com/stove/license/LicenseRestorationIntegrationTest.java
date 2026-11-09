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
import com.stove.license.core.domain.PurchaseStatus;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.domain.SuspensionRequest;
import com.stove.license.core.domain.RestorationRequest;
import com.stove.license.core.service.GrantLicense;
import com.stove.license.core.service.RegisterLicense;
import com.stove.license.core.service.ApplyLicenseSuspension;
import com.stove.license.core.service.ApplyLicenseRestoration;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import org.junit.jupiter.api.DisplayName;
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
    @Autowired RegisterLicense register;
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
        jdbc.update("DELETE FROM license_registration"); jdbc.update("DELETE FROM product_license"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }
    @Test void actualRestorationPreservesPurchaseAndOldSuspensionOrRestorationReplaysCannotUndoANewerSuspension() {
        RestorationRequest input = fixture(); PaidOrder purchase = stored(input).paidOrder(); restore.execute(input);
        assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.REGISTERED); assertThat(stored(input).paidOrder()).isEqualTo(purchase);
        restore.execute(input);
        LicenseSuspension original = suspensions.findByRequestEventId(input.suspensionRequestEventId()).orElseThrow();
        suspend.execute(original.request()); assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.REGISTERED);
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        SuspensionRequest next = new SuspensionRequest(id(), NOW.plusSeconds(1), id(), input.orderNo(), input.buyerSubject(), input.productId(), input.productCode(), input.paymentId());
        suspend.execute(next); restore.execute(input);
        assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.SUSPENDED);
        assertThat(stored(input).getSuspensionRequestEventId()).isEqualTo(next.eventId()); assertThat(completions()).isEqualTo(1);
    }
    @Test void anotherSuspensionCannotBeRestoredAndFailedValidationHasNoInboxOrCompletion() {
        RestorationRequest input = fixture();
        RestorationRequest other = new RestorationRequest(input.eventId(), input.occurredAt(), input.refundId(), input.attemptId(), input.failureEventId(),
                input.licenseId(), input.orderNo(), input.buyerSubject(), input.productId(), input.productCode(), input.paymentId(), id(), input.suspensionEventId());
        assertThatThrownBy(() -> restore.execute(other)).isInstanceOf(RuntimeException.class);
        assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.SUSPENDED); assertThat(completions()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='license-restoration'", Integer.class)).isZero();
    }
    @Test void actualStateHistoryInboxAndOutboxRollbackTogetherAndReplayCanResume() {
        RestorationRequest input = fixture();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); licenses.flush(); throw new IllegalStateException("after restored state/history/outbox flush");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThatThrownBy(() -> restore.execute(input)).isInstanceOf(IllegalStateException.class);
        assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.SUSPENDED); assertThat(completions()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM license_restoration", Integer.class)).isZero();
        reset(outbox); restore.execute(input); assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.REGISTERED); assertThat(completions()).isEqualTo(1);
    }
    @Test void concurrentDeliveriesRestoreOnceAndKeepOneCompletion() throws Exception {
        RestorationRequest input = fixture(); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> work = List.of(pool.submit(() -> { start.await(); restore.execute(input); return null; }),
                    pool.submit(() -> { start.await(); restore.execute(input); return null; }));
            start.countDown(); for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
            assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.REGISTERED); assertThat(completions()).isEqualTo(1);
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
            assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.REGISTERED); assertThat(completions()).isEqualTo(1);
        });
    }
    @Test
    @DisplayName("[D-052] License 시계가 Order보다 늦어 처리 시각이 요청 발생 시각보다 앞서도 복구한다")
    void restoresWhenTheLicenseClockIsBehindTheOrderClock() {
        // 기대: 같은 정지(licenseId·suspensionRequestEventId·suspensionEventId)로 대조된 요청이면 시계 차이와 무관하게 복구하고 두 시각을 그대로 저장한다
        // 실제: 기록 생성 가드(at < occurredAt)가 INVALID_REQUEST로 막고, 그 가드를 빼도 DB CHECK(restored_at>=request_occurred_at)가 막는다
        // 영향: 재시도 뒤 DLT로 가고 구매 근거·보유권이 정지된 채 남는다
        RestorationRequest input = fixture(NOW.plusSeconds(5));
        restore.execute(input);
        assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.REGISTERED); assertThat(completions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_occurred_at FROM license_restoration WHERE request_event_id=?", Timestamp.class, input.eventId()).toInstant()).isEqualTo(input.occurredAt());
        assertThat(jdbc.queryForObject("SELECT restored_at FROM license_restoration WHERE request_event_id=?", Timestamp.class, input.eventId()).toInstant()).isEqualTo(NOW);
    }
    @Test
    @DisplayName("[D-052] Order 시계가 License보다 늦어 요청 발생 시각이 정지 시각보다 앞서도 복구한다")
    void restoresWhenTheOrderClockIsBehindTheLicenseClock() {
        // 기대: 요청 발생 시각(Order 시계)이 실제 정지 시각(License 시계)보다 앞서도 식별자가 맞으면 복구한다
        // 실제: 서비스 가드(occurredAt < suspendedAt)가 CONFLICT로 막는다
        // 영향: 재시도 뒤 DLT로 가고 구매 근거·보유권이 정지된 채 남는다
        RestorationRequest input = fixture(NOW.minusSeconds(5));
        restore.execute(input);
        assertThat(stored(input).getStatus()).isEqualTo(PurchaseStatus.REGISTERED); assertThat(completions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='license-restoration'", Integer.class)).isEqualTo(1);
    }
    private RestorationRequest fixture() { return fixture(NOW); }
    private RestorationRequest fixture(Instant occurredAt) {
        PaidOrder purchase = paid(); grant.execute(purchase); License license = licenses.findByOrderNo(purchase.orderNo()).orElseThrow();
        register.execute(purchase.buyerSubject(), license.getLicenseId());
        SuspensionRequest request = new SuspensionRequest(id(), NOW.minusSeconds(10), id(), license.getOrderNo(), license.getBuyerSubject(),
                license.getProductId(), license.getProductCode(), license.getPaymentId());
        suspend.execute(request); LicenseSuspension suspended = suspensions.findByRequestEventId(request.eventId()).orElseThrow();
        return new RestorationRequest(id(), occurredAt, request.refundId(), id(), id(), license.getLicenseId(), license.getOrderNo(), license.getBuyerSubject(),
                license.getProductId(), license.getProductCode(), license.getPaymentId(), request.eventId(), suspended.getCompletedEventId());
    }
    private License stored(RestorationRequest input) { return licenses.findByOrderNo(input.orderNo()).orElseThrow(); }
    private int completions() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseRestored'", Integer.class); }
}
