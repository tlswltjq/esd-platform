package com.stove.license;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.LicenseSuspendedEvent;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.domain.SuspensionRequest;
import com.stove.license.core.service.ApplyLicenseSuspension;
import com.stove.license.core.service.GrantLicense;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class LicenseSuspensionIntegrationTest extends LicenseIntegrationTestBase {
    @Autowired GrantLicense grant;
    @Autowired ApplyLicenseSuspension apply;
    @Autowired LicenseRepository licenses;
    @Autowired LicenseSuspensionRepository suspensions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OutboxRecorder outbox;
    @LocalServerPort int port;

    @BeforeEach void initialize() { clear(); }
    @AfterEach void clear() {
        listeners.stop();
        jdbc.update("DELETE FROM license_suspension");
        jdbc.update("DELETE FROM license_purchase");
        jdbc.update("DELETE FROM product_license");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void aSuspendedProductRemainsVisibleAndPreservesItsPurchaseEvidence() throws Exception {
        License original = saved();
        PaidOrder purchase = original.paidOrder();
        SuspensionRequest input = request(original);
        apply.execute(input);
        License stored = licenses.findByOrderNo(original.getOrderNo()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(stored.paidOrder()).isEqualTo(purchase);
        assertThat(stored.getAcquiredAt()).isEqualTo(original.getAcquiredAt());
        assertThat(stored.getSuspensionRequestEventId()).isEqualTo(input.eventId());
        JsonNode detail = library("/" + original.getProductCode());
        assertThat(detail.path("data").path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(detail.path("data").size()).isEqualTo(6);
        JsonNode page = library("").path("data");
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(page.path("content").get(0).path("status").asText()).isEqualTo("SUSPENDED");
        LicenseSuspendedEvent event = mapper.readValue(jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type='LicenseSuspended'", String.class), LicenseSuspendedEvent.class);
        assertThat(event.requestEventId()).isEqualTo(input.eventId());
        assertThat(event.refundId()).isEqualTo(input.refundId());
        assertThat(event.licenseId()).isEqualTo(original.getLicenseId());
        assertThat(event.orderNo()).isEqualTo(original.getOrderNo());
        assertThat(event.paymentId()).isEqualTo(original.getPaymentId());
        assertThat(event.eventId()).isEqualTo(suspensions.findByRequestEventId(input.eventId()).orElseThrow().getCompletedEventId());
        grant.execute(purchase);
        assertThat(licenses.findByOrderNo(original.getOrderNo()).orElseThrow().getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        apply.execute(input);
        assertThat(completions()).isEqualTo(1);
    }

    @Test void outboxFailureRollsBackAccessStateReceiptAndInbox() {
        License license = saved();
        SuspensionRequest input = request(license);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction ->
                    doAnswer(call -> {
                        call.callRealMethod();
                        throw new IllegalStateException("abort suspension after outbox insert");
                    }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
            assertThatThrownBy(() -> apply.execute(input)).isInstanceOf(IllegalStateException.class);
            License stored = licenses.findByOrderNo(license.getOrderNo()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
            assertThat(stored.getSuspensionRequestEventId()).isNull();
            assertThat(suspensions.count()).isZero();
            assertThat(completions()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='license-suspension'", Integer.class)).isZero();
        } finally { reset(outbox); }
        apply.execute(input);
        assertThat(completions()).isEqualTo(1);
    }

    @Test void concurrentRedeliveryCreatesOneReceiptAndOneCompletion() throws Exception {
        License license = saved();
        SuspensionRequest input = request(license);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                apply.execute(input);
                written.countDown();
                await(commit);
            }));
            await(written);
            Future<?> second = pool.submit(() -> { started.countDown(); apply.execute(input); });
            await(started);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertThat(suspensions.count()).isEqualTo(1);
            assertThat(completions()).isEqualTo(1);
        } finally {
            commit.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void changedRefundEvidenceAndAnotherOperationCannotReplaceTheSuspension() {
        License license = saved();
        SuspensionRequest input = request(license);
        apply.execute(input);
        SuspensionRequest changed = new SuspensionRequest(input.eventId(), input.occurredAt(), id(), input.orderNo(),
                input.buyerSubject(), input.productId(), input.productCode(), input.paymentId());
        assertThatThrownBy(() -> apply.execute(changed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> apply.execute(request(license))).isInstanceOf(BusinessException.class);
        assertThat(suspensions.count()).isEqualTo(1);
        assertThat(completions()).isEqualTo(1);
    }

    @Test void kafkaDeliversTheValidatedRequestWithoutCreatingDuplicateCompletions() throws Exception {
        License license = saved();
        SuspensionRequest input = request(license);
        LicenseSuspensionRequestedEvent event = new LicenseSuspensionRequestedEvent(input.eventId(), input.occurredAt(),
                input.refundId(), input.orderNo(), input.buyerSubject(), input.productId(), input.productCode(), input.paymentId());
        listeners.start();
        for (int attempt = 0; attempt < 2; attempt++) {
            ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.partitionKey(), mapper.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10, TimeUnit.SECONDS);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(licenses.findByOrderNo(input.orderNo()).orElseThrow().getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
            assertThat(completions()).isEqualTo(1);
        });
    }

    private License saved() {
        PaidOrder input = paid();
        grant.execute(input);
        return licenses.findByOrderNo(input.orderNo()).orElseThrow();
    }

    private static SuspensionRequest request(License license) {
        return new SuspensionRequest(id(), Instant.parse("2026-10-26T01:00:00.123456Z"), id(), license.getOrderNo(),
                license.getBuyerSubject(), license.getProductId(), license.getProductCode(), license.getPaymentId());
    }

    private int completions() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseSuspended'", Integer.class);
    }

    private JsonNode library(String suffix) {
        return RestClient.create("http://127.0.0.1:" + port).get().uri("/api/v1/library" + suffix)
                .headers(headers -> headers.setBearerAuth(token("usr_buyer", "MEMBER")))
                .retrieve().body(JsonNode.class);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
