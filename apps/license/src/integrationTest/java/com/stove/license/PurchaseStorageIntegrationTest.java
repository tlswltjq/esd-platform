package com.stove.license;

import static com.stove.license.LicenseFixtures.id;
import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.payload.LicenseRestoredEvent;
import com.stove.common.kafka.contract.payload.PurchaseRefundedEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.license.core.domain.License;
import com.stove.license.core.domain.LicenseRegistration;
import com.stove.license.core.domain.LicenseRegistrationRepository;
import com.stove.license.core.domain.LicenseRepository;
import com.stove.license.core.domain.LicenseStatus;
import com.stove.license.core.domain.LicenseSuspension;
import com.stove.license.core.domain.LicenseSuspensionRepository;
import com.stove.license.core.domain.PaidOrder;
import com.stove.license.core.domain.PurchaseRefund;
import com.stove.license.core.domain.PurchaseStatus;
import com.stove.license.core.domain.RestorationRequest;
import com.stove.license.core.domain.SuspensionRequest;
import com.stove.license.core.service.ApplyLicenseRestoration;
import com.stove.license.core.service.ApplyLicenseSuspension;
import com.stove.license.core.service.ApplyPurchaseRefund;
import com.stove.license.core.service.GrantLicense;
import com.stove.license.core.service.RegisterLicense;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

/** 결정 89·93~97: 보관함·등록·환불 중 재구매·환불 완료를 실제 MySQL·Kafka로 확인한다. */
class PurchaseStorageIntegrationTest extends LicenseIntegrationTestBase {
    static final String BUYER = "usr_buyer";
    @Autowired GrantLicense grant;
    @Autowired RegisterLicense register;
    @Autowired ApplyLicenseSuspension suspend;
    @Autowired ApplyLicenseRestoration restore;
    @Autowired ApplyPurchaseRefund refund;
    @Autowired LicenseRepository licenses;
    @Autowired LicenseRegistrationRepository registrations;
    @Autowired LicenseSuspensionRepository suspensions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @LocalServerPort int port;

    @BeforeEach void initialize() { clear(); }
    @AfterEach void clear() {
        listeners.stop();
        jdbc.update("DELETE FROM license_approval_correction"); jdbc.update("DELETE FROM license_restoration");
        jdbc.update("DELETE FROM license_suspension"); jdbc.update("DELETE FROM license_purchase");
        jdbc.update("DELETE FROM license_registration"); jdbc.update("DELETE FROM product_license");
        jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    @DisplayName("같은 상품의 두 구매는 둘 다 보관함에 남고, 하나만 등록되며 나머지는 이미 보유로 거절된다")
    void twoPurchasesOfTheSameProductStayInStorageAndOnlyOneRegisters() {
        License first = bought();
        License second = bought();
        assertThat(List.of(status(first), status(second))).containsOnly(PurchaseStatus.STORED);
        assertThat(http("GET", "/api/v1/library", BUYER).data().path("totalElements").asInt()).isZero();
        JsonNode storage = http("GET", "/api/v1/storage", BUYER).data();
        assertThat(storage.path("totalElements").asInt()).isEqualTo(2);
        assertThat(storage.path("content").get(0).path("registrable").asBoolean()).isTrue();

        Response registered = http("POST", "/api/v1/storage/" + first.getLicenseId() + "/registration", BUYER);
        assertThat(registered.status()).isEqualTo(200);
        assertThat(registered.data().path("licenseId").asText()).isEqualTo(first.getLicenseId());
        assertThat(registered.data().path("status").asText()).isEqualTo("ACTIVE");
        Response again = http("POST", "/api/v1/storage/" + first.getLicenseId() + "/registration", BUYER);
        assertThat(again.status()).isEqualTo(200);
        assertThat(count("license_registration")).isEqualTo(1);

        JsonNode library = http("GET", "/api/v1/library", BUYER).data();
        assertThat(library.path("totalElements").asInt()).isEqualTo(1);
        assertThat(library.path("content").get(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(http("GET", "/api/v1/library/Game-1", BUYER).data().path("licenseId").asText()).isEqualTo(first.getLicenseId());

        JsonNode left = http("GET", "/api/v1/storage", BUYER).data();
        assertThat(left.path("totalElements").asInt()).isEqualTo(1);
        assertThat(left.path("content").get(0).path("licenseId").asText()).isEqualTo(second.getLicenseId());
        assertThat(left.path("content").get(0).path("orderNo").asText()).isEqualTo(second.getOrderNo());
        assertThat(left.path("content").get(0).path("status").asText()).isEqualTo("STORED");
        assertThat(left.path("content").get(0).path("registrable").asBoolean()).isFalse();

        Response owned = http("POST", "/api/v1/storage/" + second.getLicenseId() + "/registration", BUYER);
        assertThat(owned.status()).isEqualTo(409);
        assertThat(owned.body().toString()).contains("PRODUCT_ALREADY_OWNED");
        assertThat(status(second)).isEqualTo(PurchaseStatus.STORED);

        assertThat(http("POST", "/api/v1/storage/" + second.getLicenseId() + "/registration", "other").status()).isEqualTo(404);
        assertThat(http("POST", "/api/v1/storage/not-a-uuid/registration", BUYER).status()).isEqualTo(400);
        assertThat(http("GET", "/api/v1/storage", "other").data().path("totalElements").asInt()).isZero();
        assertThat(httpAs("POST", "/api/v1/storage/" + second.getLicenseId() + "/registration", token("creator", "CREATOR")).status()).isEqualTo(403);
        assertThat(httpAs("GET", "/api/v1/storage", null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("환불 중에 새로 사서 등록한 뒤 옛 구매가 복구되면, 옛 구매는 보관함으로 돌아가고 새 보유권만 남는다")
    void restorationDuringRepurchaseReturnsTheOldPurchaseToStorage() throws Exception {
        License first = bought();
        register.execute(BUYER, first.getLicenseId());
        SuspensionRequest stop = stop(first);
        suspend.execute(stop);
        assertThat(status(first)).isEqualTo(PurchaseStatus.SUSPENDED);

        License second = bought();
        LicenseRegistration repurchased = register.execute(BUYER, second.getLicenseId());
        assertThat(repurchased.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(http("GET", "/api/v1/library/Game-1", BUYER).data().path("licenseId").asText()).isEqualTo(second.getLicenseId());

        restore.execute(restoration(first, stop));

        License returned = licenses.findByOrderNo(first.getOrderNo()).orElseThrow();
        assertThat(returned.getStatus()).isEqualTo(PurchaseStatus.STORED);
        assertThat(returned.getSuspendedFrom()).isNull();
        assertThat(registrationStatuses(first)).containsExactly(LicenseStatus.ENDED);
        assertThat(registrationStatuses(second)).containsExactly(LicenseStatus.ACTIVE);
        LicenseRestoredEvent event = mapper.readValue(jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type='LicenseRestored'", String.class), LicenseRestoredEvent.class);
        assertThat(event.returnedToStorage()).isTrue();
        assertThat(event.orderNo()).isEqualTo(first.getOrderNo());
        assertThat(jdbc.queryForObject("SELECT returned_to_storage FROM license_restoration", Boolean.class)).isTrue();

        JsonNode library = http("GET", "/api/v1/library", BUYER).data();
        assertThat(library.path("totalElements").asInt()).isEqualTo(1);
        assertThat(library.path("content").get(0).path("licenseId").asText()).isEqualTo(second.getLicenseId());
        JsonNode storage = http("GET", "/api/v1/storage", BUYER).data();
        assertThat(storage.path("totalElements").asInt()).isEqualTo(1);
        assertThat(storage.path("content").get(0).path("licenseId").asText()).isEqualTo(first.getLicenseId());
        assertThat(storage.path("content").get(0).path("registrable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("환불 완료는 구매와 보유권을 끝내고 목록에서 빼며, 늦은 복구가 새 보유권을 건드리지 못한다")
    void refundCompletionEndsThePurchaseAndLateRestorationCannotTouchTheNewLicense() throws Exception {
        License first = bought();
        register.execute(BUYER, first.getLicenseId());
        SuspensionRequest stop = stop(first);
        suspend.execute(stop);
        License second = bought();
        register.execute(BUYER, second.getLicenseId());

        PurchaseRefundedEvent completed = new PurchaseRefundedEvent(id(), Instant.parse("2026-11-06T02:00:00.123456Z"), stop.refundId(),
                first.getLicenseId(), first.getOrderNo(), BUYER, first.getProductId(), first.getProductCode(), first.getPaymentId(), stop.eventId());
        listeners.start();
        for (int delivery = 0; delivery < 2; delivery++) send(completed);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(status(first)).isEqualTo(PurchaseStatus.REFUNDED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='license-purchase-refund'",
                    Integer.class)).isEqualTo(1);
        });
        listeners.stop();
        refund.execute(new PurchaseRefund(completed.eventId(), completed.occurredAt(), completed.refundId(), completed.licenseId(),
                completed.orderNo(), completed.buyerSubject(), completed.productId(), completed.productCode(), completed.paymentId(),
                completed.suspensionRequestEventId()));
        assertThat(registrationStatuses(first)).containsExactly(LicenseStatus.ENDED);
        assertThat(licenses.findByOrderNo(first.getOrderNo()).orElseThrow().getRefundedAt()).isNotNull();

        assertThatThrownBy(() -> restore.execute(restoration(first, stop))).isInstanceOf(BusinessException.class);
        assertThat(status(first)).isEqualTo(PurchaseStatus.REFUNDED);
        assertThat(registrationStatuses(second)).containsExactly(LicenseStatus.ACTIVE);
        assertThat(count("license_restoration")).isZero();
        assertThatThrownBy(() -> register.execute(BUYER, first.getLicenseId())).isInstanceOf(BusinessException.class);

        JsonNode library = http("GET", "/api/v1/library", BUYER).data();
        assertThat(library.path("totalElements").asInt()).isEqualTo(1);
        assertThat(library.path("content").get(0).path("licenseId").asText()).isEqualTo(second.getLicenseId());
        assertThat(http("GET", "/api/v1/storage", BUYER).data().path("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("같은 상품의 두 보관함 항목을 동시에 등록해도 쓸 수 있는 보유권은 하나뿐이다")
    void concurrentRegistrationsLeaveExactlyOneActiveLicense() throws Exception {
        License first = bought();
        License second = bought();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> work = List.of(
                    pool.submit(() -> { start.await(); return attempt(first); }),
                    pool.submit(() -> { start.await(); return attempt(second); }));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : work) if (future.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM license_registration WHERE status='ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(List.of(status(first), status(second))).containsExactlyInAnyOrder(PurchaseStatus.REGISTERED, PurchaseStatus.STORED);
    }

    private boolean attempt(License purchase) {
        try {
            register.execute(BUYER, purchase.getLicenseId());
            return true;
        } catch (BusinessException | DataAccessException failure) {
            return false;
        }
    }

    private License bought() {
        PaidOrder order = paid(BUYER, 1L, "Game-1");
        grant.execute(order);
        return licenses.findByOrderNo(order.orderNo()).orElseThrow();
    }

    private PurchaseStatus status(License purchase) {
        return licenses.findByOrderNo(purchase.getOrderNo()).orElseThrow().getStatus();
    }

    private List<LicenseStatus> registrationStatuses(License purchase) {
        return registrations.findAll().stream().filter(row -> row.getLicenseId().equals(purchase.getLicenseId()))
                .map(LicenseRegistration::getStatus).toList();
    }

    private SuspensionRequest stop(License purchase) {
        return new SuspensionRequest(id(), Instant.parse("2026-11-06T01:00:00.123456Z"), id(), purchase.getOrderNo(), BUYER,
                purchase.getProductId(), purchase.getProductCode(), purchase.getPaymentId());
    }

    private RestorationRequest restoration(License purchase, SuspensionRequest stop) {
        LicenseSuspension suspended = suspensions.findByRequestEventId(stop.eventId()).orElseThrow();
        return new RestorationRequest(id(), suspended.getSuspendedAt(), stop.refundId(), id(), id(), purchase.getLicenseId(),
                purchase.getOrderNo(), BUYER, purchase.getProductId(), purchase.getProductCode(), purchase.getPaymentId(),
                stop.eventId(), suspended.getCompletedEventId());
    }

    private void send(PurchaseRefundedEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.partitionKey(), mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    record Response(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
    }

    private Response http(String method, String path, String subject) {
        return httpAs(method, path, token(subject, "MEMBER"));
    }

    private Response httpAs(String method, String path, String bearer) {
        return RestClient.create().method(HttpMethod.valueOf(method)).uri("http://127.0.0.1:" + port + path)
                .headers(headers -> { if (bearer != null) headers.setBearerAuth(bearer); })
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Response(response.getStatusCode().value(), bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
                });
    }
}
