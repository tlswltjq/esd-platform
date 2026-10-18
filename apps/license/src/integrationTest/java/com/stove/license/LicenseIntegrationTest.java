package com.stove.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.license.core.domain.*;
import com.stove.license.core.service.GrantLicense;
import com.stove.license.core.service.ReadLicense;
import com.stove.license.core.service.SearchLicenses;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;

import static com.stove.license.LicenseFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LicenseIntegrationTest extends LicenseIntegrationTestBase {
    @MockitoSpyBean
    GrantLicense grant;
    @Autowired
    SearchLicenses search;
    @Autowired
    ReadLicense read;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaListenerEndpointRegistry listeners;
    @Autowired
    PlatformTransactionManager transactions;
    @MockitoSpyBean
    LicenseRepository licenses;
    @MockitoSpyBean
    ProcessedEventGuard inbox;
    @Autowired
    org.springframework.context.ApplicationContext context;
    @LocalServerPort
    int port;

    @BeforeEach
    void clean() {
        listeners.stop();
        jdbc.update("DELETE FROM license_purchase");
        jdbc.update("DELETE FROM product_license");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    void counts(int rights, int markers) {
        assertThat(count("product_license")).isEqualTo(rights);
        assertThat(count("license_purchase")).isZero();
        assertThat(count("processed_event")).isEqualTo(markers);
        assertThat(count("outbox_event")).isZero();
    }

    PaidOrder changed(PaidOrder p, String field, Object value) throws Exception {
        ObjectNode body = json.valueToTree(p);
        body.set(field, json.valueToTree(value));
        return json.treeToValue(body, PaidOrder.class);
    }

    @Test
    void duplicateDeliveryPreservesPublicIdTimestampsAndAllEvidence() {
        PaidOrder p = paid();
        grant.execute(p);
        License license = read.execute(p.buyerSubject(), p.productCode());
        Instant received = licenses.findByOrderNo(p.orderNo()).orElseThrow().getReceivedAt();
        grant.execute(p);
        grant.execute(p);
        counts(1, 1);
        License duplicate = read.execute(p.buyerSubject(), p.productCode());
        assertThat(duplicate.getLicenseId()).isEqualTo(license.getLicenseId());
        assertThat(duplicate.getAcquiredAt()).isEqualTo(license.getAcquiredAt());
        License evidence = licenses.findByOrderNo(p.orderNo()).orElseThrow();
        assertThat(evidence.paidOrder()).isEqualTo(p);
        assertThat(evidence.getReceivedAt()).isEqualTo(received);
    }

    @Test
    void anotherOrderForTheSameProductIsRejectedWithoutChangingTheOriginalFact() throws Exception {
        PaidOrder first = paid();
        grant.execute(first);
        License original = read.execute(first.buyerSubject(), first.productCode());
        PaidOrder later = changed(changed(changed(paid(), "title", "Older title"), "contentVersion", 1L), "occurredAt", first.occurredAt().minusSeconds(100));
        assertThatThrownBy(() -> grant.execute(later)).isInstanceOf(BusinessException.class);
        grant.execute(paid("other", 1L, "Game-1"));
        counts(2, 2);
        License unchanged = read.execute(first.buyerSubject(), first.productCode());
        assertThat(unchanged.getLicenseId()).isEqualTo(original.getLicenseId());
        assertThat(unchanged.getTitle()).isEqualTo(first.title());
        assertThat(unchanged.getAcquiredAt()).isEqualTo(original.getAcquiredAt());
        assertThat(licenses.findByOrderNo(later.orderNo())).isEmpty();
        assertThat(unchanged.paidOrder()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "occurredAt", "requestEventId", "paymentResultEventId", "paymentId",
            "buyerSubject", "productId", "productCode", "title", "contentVersion", "amount"})
    void sameOrderMustMatchTheEntireOriginalFact(String field) throws Exception {
        PaidOrder p = paid();
        grant.execute(p);
        Object value = switch (field) {
            case "occurredAt" -> p.occurredAt().plusSeconds(1);
            case "productId", "contentVersion", "amount" -> 77L;
            default -> "different";
        };
        PaidOrder conflict = changed(p, field, value);
        assertThatThrownBy(() -> grant.execute(conflict)).isInstanceOf(BusinessException.class);
        counts(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "requestEventId", "paymentResultEventId", "paymentId"})
    void sourceIdentifiersCannotBeReusedForAnotherOrder(String field) throws Exception {
        PaidOrder first = paid();
        grant.execute(first);
        PaidOrder conflict = changed(paid(), field, json.valueToTree(first).path(field).asText());
        assertThatThrownBy(() -> grant.execute(conflict)).isInstanceOf(BusinessException.class);
        counts(1, 1);
    }

    @Test
    void productIdAndCodeMustReferToTheSameOwnedRight() {
        grant.execute(paid());
        for (PaidOrder p : List.of(paid("usr_buyer", 1L, "Game-2"), paid("usr_buyer", 2L, "Game-1")))
            assertThatThrownBy(() -> grant.execute(p)).isInstanceOf(BusinessException.class);
        counts(1, 1);
    }

    @Test
    void orphanInboxAndMissingInboxCannotHideInconsistency() {
        PaidOrder p = paid();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> inbox.firstDelivery(p.eventId(), GrantLicense.CONSUMER_GROUP, EventType.ORDER_PAID));
        assertThatThrownBy(() -> grant.execute(p)).isInstanceOf(BusinessException.class);
        counts(0, 1);
        jdbc.update("DELETE FROM processed_event");
        grant.execute(p);
        jdbc.update("DELETE FROM processed_event");
        assertThatThrownBy(() -> grant.execute(p)).isInstanceOf(BusinessException.class);
        counts(1, 0);
    }

    @Test
    void changedStoredProductIsRejectedOnRedelivery() {
        PaidOrder p = paid();
        grant.execute(p);
        jdbc.update("UPDATE product_license SET product_code='Game-2'");
        assertThatThrownBy(() -> grant.execute(p)).isInstanceOf(BusinessException.class);
        counts(1, 1);
    }

    @Test
    void inboxWithoutLicenseCannotBeAcceptedAsADuplicate() {
        PaidOrder p = paid();
        grant.execute(p);
        jdbc.update("DELETE FROM product_license");
        assertThatThrownBy(() -> grant.execute(p)).isInstanceOf(BusinessException.class);
        counts(0, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inbox", "license"})
    void failureAfterEachWriteRollsBackTheWholeGrantAndFreshTransactionRecovers(String stage) {
        PaidOrder p = paid();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean();
        tx.executeWithoutResult(status -> {
            if (stage.equals("inbox")) doAnswer(inv -> {
                inv.callRealMethod();
                assertThat(count("processed_event")).isEqualTo(1);
                written.set(true);
                throw new IllegalStateException("after inbox");
            }).when(inbox).firstDelivery(anyString(), anyString(), anyString());
            if (stage.equals("license")) doAnswer(inv -> {
                mockingDetails(licenses).getMockCreationSettings().getDefaultAnswer().answer(inv);
                assertThat(count("product_license")).isEqualTo(1);
                written.set(true);
                throw new IllegalStateException("after license");
            }).when(licenses).save(any(License.class));
        });
        try {
            assertThatThrownBy(() -> grant.execute(p)).isInstanceOf(RuntimeException.class);
            counts(0, 0);
        } finally {
            reset(inbox, licenses);
        }
        assertThat(written).isTrue();
        grant.execute(p);
        counts(1, 1);
    }

    @Test
    void abortAfterSuccessfulFlushRollsBackLicenseAndInbox() {
        PaidOrder p = paid();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            grant.execute(p);
            counts(1, 1);
            throw new IllegalStateException("abort before commit");
        })).isInstanceOf(IllegalStateException.class);
        counts(0, 0);
        grant.execute(p);
        counts(1, 1);
    }

    @Test
    void absentRightCreationRaceRollsBackLoserAndConvergesOnFreshTransaction() throws Exception {
        PaidOrder a = paid();
        PaidOrder b = paid();
        CyclicBarrier absent = new CyclicBarrier(2);
        doAnswer(inv -> {
            absent.await(15, TimeUnit.SECONDS);
            return mockingDetails(licenses).getMockCreationSettings().getDefaultAnswer().answer(inv);
        })
                .when(licenses).save(any(License.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of(pool.submit(() -> attempt(a)), pool.submit(() -> attempt(b)));
            int successes = 0;
            for (Future<Boolean> f : futures) if (f.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            counts(1, 1);
        } finally {
            reset(licenses);
            pool.shutdownNow();
        }
        License winner = licenses.findAll().getFirst();
        PaidOrder committed = winner.getOrderNo().equals(a.orderNo()) ? a : b;
        PaidOrder rejected = committed == a ? b : a;
        grant.execute(committed);
        assertThatThrownBy(() -> grant.execute(rejected)).isInstanceOf(BusinessException.class);
        counts(1, 1);
    }

    boolean attempt(PaidOrder p) {
        try {
            grant.execute(p);
            return true;
        } catch (org.springframework.dao.DataAccessException | BusinessException e) {
            return false;
        }
    }

    @Test
    void simultaneousSameEventCommitsOneEvidenceAndReplaysSafely() throws Exception {
        PaidOrder p = paid();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = List.of(pool.submit(() -> {
                start.await();
                return attempt(p);
            }), pool.submit(() -> {
                start.await();
                return attempt(p);
            }));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> f : futures) if (f.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isGreaterThanOrEqualTo(1);
            grant.execute(p);
            counts(1, 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ownerAndCodeAreComparedExactlyAndPagesHaveStableTies() {
        grant.execute(paid());
        grant.execute(paid("USR_BUYER", 1L, "Game-1"));
        grant.execute(paid("usr_buyer", 2L, "game-1"));
        jdbc.update("UPDATE product_license SET acquired_at='2026-10-17 00:00:00'");
        assertThat(search.execute("usr_buyer", 0, 1).getContent().getFirst().getProductId()).isEqualTo(2L);
        assertThat(search.execute("usr_buyer", 1, 1).getContent().getFirst().getProductId()).isEqualTo(1L);
        assertThat(search.execute("usr_buyer", 0, 20).getTotalElements()).isEqualTo(2);
        assertThat(search.execute("USR_BUYER", 0, 20).getTotalElements()).isEqualTo(1);
    }

    record Response(int status, JsonNode body) {
        JsonNode data() {
            return body.path("data");
        }
    }

    Response http(String method, String path, String bearer) {
        return RestClient.create().method(org.springframework.http.HttpMethod.valueOf(method)).uri("http://localhost:" + port + path)
                .headers(h -> {
                    if (bearer != null) h.setBearerAuth(bearer);
                    h.set("X-Member-Id", "other");
                })
                .exchange((request, response) -> new Response(response.getStatusCode().value(), json.readTree(response.getBody())));
    }

    @Test
    void signedJwtOwnershipPagingAndClosedPaths() {
        grant.execute(paid());
        String bearer = token("usr_buyer", "MEMBER");
        Response detail = http("GET", "/api/v1/library/Game-1", bearer);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().size()).isEqualTo(5);
        assertThat(detail.body().toString()).doesNotContain("buyerSubject", "orderNo", "eventId", "amount", "paymentId");
        assertThat(http("GET", "/api/v1/library?buyerSubject=other&memberId=999", bearer).data().path("totalElements").asInt()).isEqualTo(1);
        assertThat(http("GET", "/api/v1/library/Game-1", token("other", "MEMBER")).status()).isEqualTo(404);
        assertThat(http("GET", "/api/v1/library/game-1", bearer).status()).isEqualTo(404);
        assertThat(http("GET", "/api/v1/library", null).status()).isEqualTo(401);
        assertThat(http("GET", "/api/v1/library", token("creator", "CREATOR")).status()).isEqualTo(403);
        for (String query : List.of("page=-1", "size=0", "size=101", "page=bad"))
            assertThat(http("GET", "/api/v1/library?" + query, bearer).status()).isEqualTo(400);
        assertThat(http("GET", "/api/v1/library/bad!code", bearer).status()).isEqualTo(400);
        for (String path : List.of("/api/v1/library", "/api/v1/library/Game-1/revoke", "/api/v1/ops/dlt", "/api/v1/licenses"))
            assertThat(http("POST", path, bearer).status()).isEqualTo(403);
        assertThat(http("GET", "/api/v1/ops/dlt", token("admin", "ADMIN")).status()).isEqualTo(403);
    }

    @Test
    void rejectsWrongIssuerAudienceExpiredAndTamperedSignatures() {
        String valid = token("usr_buyer", "MEMBER");
        for (String invalid : List.of(token("usr_buyer", "MEMBER", "https://wrong", "esd-api", Instant.now().plusSeconds(300)),
                token("usr_buyer", "MEMBER", ISSUER, "wrong", Instant.now().plusSeconds(300)),
                token("usr_buyer", "MEMBER", ISSUER, "esd-api", Instant.now().minusSeconds(300)), valid.substring(0, valid.length() - 8) + "badbadbad"))
            assertThat(http("GET", "/api/v1/library", invalid).status()).isEqualTo(401);
        counts(0, 0);
    }

    @Test
    void legacyOutboxRelayAndOperationsAreDisabledByDefault() {
        assertThat(context.getBeansOfType(com.stove.common.messaging.outbox.OutboxRelay.class)).isEmpty();
        assertThat(context.getBeansOfType(com.stove.common.messaging.ops.OutboxOpsController.class)).isEmpty();
        jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at) VALUES('old','License','legacy','LicenseIssued','stove.license.v1','legacy','{}','PENDING',NOW())");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id='old'", String.class)).isEqualTo("PENDING");
    }

    @Test
    void openApiMatchesSnapshot() {
        OpenApiSnapshot.verify(port, "license");
    }

    void send(OrderPaidEvent e) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(e.topic(), e.partitionKey(), json.writeValueAsString(e));
        record.headers().add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, e.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(15, TimeUnit.SECONDS);
    }

    @Test
    void conflictGoesToDltAndOriginalReplayRecoversInANewTransaction() throws Exception {
        PaidOrder p = paid();
        grant.execute(p);
        // A damaged Inbox is rejected; restoring it allows the exact original to be consumed again.
        jdbc.update("DELETE FROM processed_event");
        HashMap<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id", "license-dlt-" + id());
        config.put("auto.offset.reset", "earliest");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(Topics.ORDER + ".DLT"));
            listeners.start();
            send(event(p));
            boolean found = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (!found && System.nanoTime() < deadline)
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500)))
                    if (record.value().contains(p.eventId())) {
                        assertThat(json.readValue(record.value(), OrderPaidEvent.class)).isEqualTo(event(p));
                        found = true;
                    }
            assertThat(found).isTrue();
            counts(1, 0);
            new TransactionTemplate(transactions).executeWithoutResult(tx -> inbox.firstDelivery(p.eventId(), GrantLicense.CONSUMER_GROUP, EventType.ORDER_PAID));
            clearInvocations(grant);
            send(event(p));
            send(event(p));
            verify(grant, timeout(20000).atLeast(2)).execute(p);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> counts(1, 1));
        } finally {
            listeners.stop();
        }
    }
}
