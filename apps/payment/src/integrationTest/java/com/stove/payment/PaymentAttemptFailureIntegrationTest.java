package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.PortOneFixtures.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.testcontainers.SharedContainers;
import com.stove.payment.core.domain.FailedPaymentAttempt;
import com.stove.payment.core.domain.FailedPaymentAttemptRepository;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.port.PaymentAttemptLookup;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.payment.pg.provider=PORTONE_TEST", "stove.payment.processing-enabled=false",
        "stove.outbox.relay-enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class PaymentAttemptFailureIntegrationTest {
    private static final Map<String, String> PAYMENTS = new ConcurrentHashMap<>();
    private static final Map<String, String> HISTORIES = new ConcurrentHashMap<>();
    private static final Map<String, Integer> HISTORY_STATUS = new ConcurrentHashMap<>();
    private static final AtomicInteger PAYMENT_READS = new AtomicInteger();
    private static final AtomicInteger HISTORY_READS = new AtomicInteger();
    private static final AtomicInteger WRITES = new AtomicInteger();
    private static final AtomicReference<CountDownLatch> HISTORY_ENTERED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> HISTORY_RELEASE = new AtomicReference<>();
    private static final HttpServer PG;
    static {
        try {
            PG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PG.createContext("/payments/", exchange -> {
                String key = exchange.getRequestURI().getPath().substring("/payments/".length()); boolean history = key.endsWith("/transactions");
                if (history) key = key.substring(0, key.length() - "/transactions".length());
                boolean get = "GET".equals(exchange.getRequestMethod());
                if (!get) WRITES.incrementAndGet(); else if (history) HISTORY_READS.incrementAndGet(); else PAYMENT_READS.incrementAndGet();
                if (history && HISTORY_ENTERED.get() != null) { HISTORY_ENTERED.get().countDown(); await(HISTORY_RELEASE.get()); }
                String body = history ? HISTORIES.get(key) : PAYMENTS.get(key);
                int status = !get ? 405 : history ? HISTORY_STATUS.getOrDefault(key, body == null ? 404 : 200) : body == null ? 404 : 200;
                byte[] bytes = (status == 200 ? body : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            PG.start(); Runtime.getRuntime().addShutdownHook(new Thread(() -> PG.stop(0)));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("stove.payment.portone.base-url", () -> "http://127.0.0.1:" + PG.getAddress().getPort());
        registry.add("stove.payment.portone.api-secret", () -> "attempt-test-secret"); registry.add("stove.payment.portone.store-id", () -> STORE);
        registry.add("stove.payment.portone.channel-key", () -> CHANNEL); registry.add("stove.payment.portone.webhook-secret", () -> SECRET);
        registry.add("stove.payment.portone.webhook-url", () -> "https://payment-test.example/api/v1/payments/webhooks/portone");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> PaymentIntegrationTestBase.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + PaymentIntegrationTestBase.JWKS.getAddress().getPort() + "/jwks");
    }
    @Autowired ReceiveOrderPayment receive;
    @Autowired ResolvePayment resolve;
    @Autowired PaymentRepository payments;
    @MockitoSpyBean FailedPaymentAttemptRepository failures;
    @MockitoSpyBean PaymentAttemptLookup attemptLookup;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        CountDownLatch release = HISTORY_RELEASE.get(); if (release != null) release.countDown(); HISTORY_ENTERED.set(null); HISTORY_RELEASE.set(null);
        reset(failures, attemptLookup); jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
        PAYMENTS.clear(); HISTORIES.clear(); HISTORY_STATUS.clear(); PAYMENT_READS.set(0); HISTORY_READS.set(0); WRITES.set(0);
    }

    @Test void failedAttemptKeepsTheSameCheckoutAndPendingOrderContractWithoutPublishingFailure() {
        Payment payment = received(); JsonNode checkout = get(checkout(payment)).data();
        PAYMENTS.put(payment.getPaymentId(), failed(payment, "tx-failed", 1).replace("\"id\":\"tx-failed\"", "\"id\":\"" + payment.getPaymentId() + "\""));
        history(payment, failed(payment, "tx-failed", 1)); resolve.execute(payment.getPaymentId());
        Payment stored = stored(payment); assertThat(stored.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getFailureReasonCode()).isNull(); assertThat(stored.getResultEventId()).isNull(); assertThat(stored.orderRequest()).isEqualTo(payment.orderRequest());
        assertThat(get(checkout(payment)).data()).isEqualTo(checkout); assertThat(get(historyPath(payment)).data().path("totalElements").asInt()).isEqualTo(1);
        assertThat(count("outbox_event")).isZero(); assertThat(payments.count()).isEqualTo(1); assertThat(WRITES.get()).isZero();
    }

    @Test void pollingRecoversMissedFailuresAndRedeliveryIsStableWithBoundedPrivateReadOnlyHistory() {
        Payment payment = received(); history(payment, failed(payment, "tx-first", 1), failed(payment, "tx-second", 4));
        resolve.execute(payment.getPaymentId()); JsonNode first = get(historyPath(payment) + "?size=1").data();
        assertThat(first.path("totalElements").asInt()).isEqualTo(2); assertThat(first.path("collectionPending").asBoolean()).isFalse();
        assertThat(first.path("checkedAt").isTextual()).isTrue(); JsonNode second = get(historyPath(payment) + "?size=1&page=1").data();
        assertThat(first.path("content").get(0).path("requestedAt").asText()).isGreaterThan(second.path("content").get(0).path("requestedAt").asText());
        resolve.execute(payment.getPaymentId()); assertThat(get(historyPath(payment) + "?size=1").data().path("content")).isEqualTo(first.path("content"));
        int reads = PAYMENT_READS.get() + HISTORY_READS.get(); Map<String, List<Map<String, Object>>> before = snapshot(); Response response = get(historyPath(payment));
        assertThat(response.cache()).isEqualTo("no-store"); assertThat(response.data().path("content").get(0).path("code").asText()).isEqualTo("PAYMENT_ATTEMPT_FAILED");
        for (String hidden : List.of("supplierAttemptId", "pgTxId", "pgMessage", "pgCode", "failure", "buyerSubject", "requestEventId"))
            assertThat(response.data().path("content").get(0).has(hidden)).as(hidden).isFalse();
        assertThat(snapshot()).isEqualTo(before); assertThat(PAYMENT_READS.get() + HISTORY_READS.get()).isEqualTo(reads); assertThat(failures.count()).isEqualTo(2);
    }

    @Test void failureThenSuccessPublishesOneOriginalCompletionAndKeepsHistoricalFailures() {
        Payment payment = received(); history(payment, failed(payment, "tx-first", 1)); resolve.execute(payment.getPaymentId());
        JsonNode old = get(historyPath(payment)).data().path("content");
        PAYMENTS.put(payment.getPaymentId(), paid(payment)); resolve.execute(payment.getPaymentId()); resolve.execute(payment.getPaymentId());
        Payment current = stored(payment); assertThat(current.getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(current.getFailureReason()).isNull();
        assertThat(current.getPaymentId()).isEqualTo(payment.getPaymentId()); assertThat(current.orderRequest()).isEqualTo(payment.orderRequest());
        assertThat(get(historyPath(payment)).data().path("content")).isEqualTo(old); assertThat(get(checkout(payment)).status()).isEqualTo(409);
        assertCompletion(payment); assertThat(WRITES.get()).isZero();
    }

    @Test void approvalAndOutboxCommitBeforeSlowHistoryHttpThenHistoryCanResumeAfterSuccess() throws Exception {
        Payment payment = received(); PAYMENTS.put(payment.getPaymentId(), paid(payment)); HISTORY_STATUS.put(payment.getPaymentId(), 503);
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1); HISTORY_ENTERED.set(entered); HISTORY_RELEASE.set(release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> work = pool.submit(() -> resolve.execute(payment.getPaymentId())); await(entered);
            assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertCompletion(payment);
            assertThat(stored(payment).getNextFailureLookupAt()).isNotNull(); assertThat(get("/api/v1/payments/" + payment.getOrderNo()).data().path("status").asText()).isEqualTo("PAID");
            release.countDown(); work.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); HISTORY_ENTERED.set(null); HISTORY_RELEASE.set(null); pool.shutdownNow(); }
        assertThat(get(historyPath(payment)).data().path("collectionPending").asBoolean()).isTrue();
        int paymentReads = PAYMENT_READS.get(); HISTORY_STATUS.clear(); history(payment, failed(payment, "tx-before-paid", 1)); PAYMENTS.put(payment.getPaymentId(), "{\"status\":\"CANCELLED\"}");
        jdbc.update("UPDATE purchase_payment SET next_failure_lookup_at=? WHERE payment_id=?", Timestamp.from(Payment.now().minusSeconds(1)), payment.getPaymentId());
        resolve.execute();
        assertThat(PAYMENT_READS.get()).isEqualTo(paymentReads); assertThat(HISTORY_READS.get()).isEqualTo(2);
        assertThat(stored(payment).getNextFailureLookupAt()).isNull(); assertThat(get(historyPath(payment)).data().path("totalElements").asInt()).isEqualTo(1);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertCompletion(payment);
    }

    @Test void malformedHistoryCannotDelayVerifiedApprovalOrInventAnAttempt() {
        Payment payment = received(); PAYMENTS.put(payment.getPaymentId(), paid(payment)); history(payment, failed(payment, "tx-invalid", 1).replace(STORE, "other-store"));
        resolve.execute(payment.getPaymentId()); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(failures.count()).isZero();
        assertThat(stored(payment).getFailureHistoryCheckedAt()).isNull(); assertThat(stored(payment).getNextFailureLookupAt()).isNotNull(); assertCompletion(payment);
        history(payment, failed(payment, "tx-valid", 1)); resolve.execute(payment.getPaymentId());
        assertThat(failures.count()).isEqualTo(1); assertThat(stored(payment).getNextFailureLookupAt()).isNull(); assertCompletion(payment);
    }

    @Test void aHistoryResponseStartedWhilePendingCannotClearTheLaterApprovalHistoryRetry() {
        Payment payment = received(); history(payment); AtomicBoolean earlierLookup = new AtomicBoolean(true);
        doAnswer(call -> {
            if (!earlierLookup.getAndSet(false)) return call.callRealMethod();
            // Interleave another resolver after the earlier empty response, before its DB transaction.
            PAYMENTS.put(payment.getPaymentId(), paid(payment)); history(payment, failed(payment, "tx-before-approval", 1));
            HISTORY_STATUS.put(payment.getPaymentId(), 503); resolve.execute(payment.getPaymentId());
            assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertCompletion(payment);
            assertThat(stored(payment).getNextFailureLookupAt()).isNotNull(); HISTORY_STATUS.clear();
            return List.of();
        }).when(attemptLookup).findFailed(payment.chargeRequest());
        try {
            resolve.execute(payment.getPaymentId());
            assertThat(stored(payment).getNextFailureLookupAt()).isNotNull();
        } finally { reset(attemptLookup); }
        int paymentReads = PAYMENT_READS.get(); resolve.execute(payment.getPaymentId());
        assertThat(PAYMENT_READS.get()).isEqualTo(paymentReads); assertThat(failures.count()).isEqualTo(1);
        assertThat(stored(payment).getNextFailureLookupAt()).isNull(); assertCompletion(payment);
    }

    @Test void unknownUnavailableAndUnpaidDoNotBecomeFailuresOrCloseTheCheckout() {
        Payment payment = received();
        for (String state : List.of("READY", "PAY_PENDING", "FUTURE")) {
            PAYMENTS.put(payment.getPaymentId(), "{\"status\":\"" + state + "\"}"); history(payment);
            resolve.execute(payment.getPaymentId()); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
        PAYMENTS.clear(); HISTORY_STATUS.put(payment.getPaymentId(), 503); resolve.execute(payment.getPaymentId());
        assertThat(failures.count()).isZero(); assertThat(count("outbox_event")).isZero(); assertThat(get(checkout(payment)).status()).isEqualTo(200);
        assertThat(get(historyPath(payment)).data().path("collectionPending").asBoolean()).isTrue(); assertThat(WRITES.get()).isZero();
    }

    @Test void aChangedSupplierAttemptCannotRewriteEvidenceOrUndoLaterMoneyConfirmation() {
        Payment payment = received(); history(payment, failed(payment, "tx-fixed", 1)); resolve.execute(payment.getPaymentId());
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM failed_payment_attempt");
        history(payment, failed(payment, "tx-fixed", 4)); PAYMENTS.put(payment.getPaymentId(), paid(payment)); resolve.execute(payment.getPaymentId());
        assertThat(jdbc.queryForList("SELECT * FROM failed_payment_attempt")).isEqualTo(before);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(stored(payment).getNextFailureLookupAt()).isNotNull(); assertCompletion(payment);
    }

    @Test void concurrentCollectionAndApprovalConvergeToOneRecordPerAttemptAndOneCompletion() throws Exception {
        Payment payment = received(); history(payment, failed(payment, "tx-one", 1), failed(payment, "tx-two", 4)); PAYMENTS.put(payment.getPaymentId(), paid(payment));
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> one = pool.submit(() -> { await(start); resolve.execute(payment.getPaymentId()); });
            Future<?> two = pool.submit(() -> { await(start); resolve.execute(payment.getPaymentId()); });
            start.countDown(); one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
            assertThat(failures.count()).isEqualTo(2); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertCompletion(payment);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void realHistoryFlushFailureRollsBackItsRowsAndCollectionCheckpointWhileApprovalRemainsCommitted() {
        Payment payment = received(); history(payment, failed(payment, "tx-one", 1)); PAYMENTS.put(payment.getPaymentId(), paid(payment));
        doAnswer(call -> {
            FailedPaymentAttempt attempt = call.getArgument(0, FailedPaymentAttempt.class); entityManager.persist(attempt); entityManager.flush();
            assertThat(count("failed_payment_attempt")).isEqualTo(1); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
            throw new IllegalStateException("after real history flush");
        }).when(failures).save(any(FailedPaymentAttempt.class));
        try {
            resolve.execute(payment.getPaymentId()); assertThat(failures.count()).isZero(); assertThat(stored(payment).getFailureHistoryCheckedAt()).isNull();
            assertThat(stored(payment).getNextFailureLookupAt()).isNotNull(); assertCompletion(payment);
        } finally { reset(failures); }
        resolve.execute(payment.getPaymentId()); assertThat(failures.count()).isEqualTo(1); assertThat(stored(payment).getFailureHistoryCheckedAt()).isNotNull(); assertCompletion(payment);
    }

    @Test void aSupplierAttemptIdCannotBeReassignedToAnotherPayment() {
        Payment first = received(); Payment second = received(); history(first, failed(first, "tx-global", 1)); resolve.execute(first.getPaymentId());
        history(second, failed(second, "tx-global", 1)); PAYMENTS.put(second.getPaymentId(), paid(second)); resolve.execute(second.getPaymentId());
        assertThat(failures.count()).isEqualTo(1); assertThat(failures.findBySupplierAttemptId("tx-global").orElseThrow().getPaymentId()).isEqualTo(first.getPaymentId());
        assertThat(stored(second).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(stored(second).getNextFailureLookupAt()).isNotNull(); assertCompletion(second);
    }

    @Test void historyIsOwnerOnlyAndInvalidTokensInputsAndWritesLeaveRecordsUnchanged() {
        Payment payment = received(); history(payment, failed(payment, "tx-one", 1)); resolve.execute(payment.getPaymentId()); Map<String, List<Map<String, Object>>> before = snapshot();
        String path = historyPath(payment); assertThat(http("GET", path, null).status()).isEqualTo(401);
        assertThat(http("GET", path, PaymentIntegrationTestBase.token("other", "MEMBER")).status()).isEqualTo(404);
        for (String role : List.of("ADMIN", "CREATOR", "REVIEWER")) assertThat(http("GET", path, PaymentIntegrationTestBase.token("usr_buyer", role)).status()).isEqualTo(403);
        for (String token : List.of(PaymentIntegrationTestBase.token("usr_buyer", "MEMBER", "https://wrong", "esd-api", Instant.now().plusSeconds(60)),
                PaymentIntegrationTestBase.token("usr_buyer", "MEMBER", PaymentIntegrationTestBase.ISSUER, "wrong", Instant.now().plusSeconds(60)),
                PaymentIntegrationTestBase.token("usr_buyer", "MEMBER", PaymentIntegrationTestBase.ISSUER, "esd-api", Instant.now().minusSeconds(120))))
            assertThat(http("GET", path, token).status()).isEqualTo(401);
        for (String suffix : List.of("?page=-1", "?size=0", "?size=101", "?page=bad")) assertThat(get(path + suffix).status()).isEqualTo(400);
        assertThat(get("/api/v1/payments/bad/attempt-failures").status()).isEqualTo(400); assertThat(get("/api/v1/payments/" + id() + "/attempt-failures").status()).isEqualTo(404);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) assertThat(http(method, path, member()).status()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void v28PreservesOriginalPurchasesAndSchedulesOnlyReadOnlyHistoryWithoutInventingAttempts() {
        Payment pending = received(); Payment paid = received(); PAYMENTS.put(paid.getPaymentId(), paid(paid)); history(paid); resolve.execute(paid.getPaymentId());
        Payment simulated = Payment.receive(request(), "SIMULATED"); payments.saveAndFlush(simulated);
        MySQLContainer<?> mysql = SharedContainers.MYSQL; String schema = "attempt_upgrade_" + id().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/"; String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword())); admin.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword()); JdbcTemplate legacy = new JdbcTemplate(source);
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath(); Flyway.configure().dataSource(source).locations(location).target("27").load().migrate();
            for (String table : List.of("purchase_payment", "processed_event", "outbox_event")) {
                List<String> columns = legacy.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? AND generation_expression='' ORDER BY ordinal_position", String.class, schema, table);
                String copy = String.join(",", columns); legacy.update("INSERT INTO " + table + "(" + copy + ") SELECT " + copy + " FROM " + mysql.getDatabaseName() + "." + table);
            }
            List<Map<String, Object>> before = legacy.queryForList("SELECT * FROM purchase_payment ORDER BY id"); List<Map<String, Object>> oldOutbox = legacy.queryForList("SELECT * FROM outbox_event ORDER BY id");
            Flyway flyway = Flyway.configure().dataSource(source).locations(location).load(); flyway.migrate(); flyway.validate();
            List<Map<String, Object>> after = legacy.queryForList("SELECT * FROM purchase_payment ORDER BY id");
            for (int i = 0; i < before.size(); i++) assertThat(after.get(i)).containsAllEntriesOf(before.get(i));
            assertThat(legacy.queryForList("SELECT * FROM outbox_event ORDER BY id")).isEqualTo(oldOutbox); assertThat(legacy.queryForObject("SELECT COUNT(*) FROM failed_payment_attempt", Integer.class)).isZero();
            for (Payment value : List.of(pending, paid)) assertThat(legacy.queryForObject("SELECT next_failure_lookup_at FROM purchase_payment WHERE payment_id=?", Timestamp.class, value.getPaymentId())).isNotNull();
            assertThat(legacy.queryForObject("SELECT next_failure_lookup_at FROM purchase_payment WHERE payment_id=?", Timestamp.class, simulated.getPaymentId())).isNull();
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM purchase_payment WHERE failure_history_checked_at IS NOT NULL", Integer.class)).isZero();
            String insert = "INSERT INTO failed_payment_attempt(attempt_id,payment_id,supplier_attempt_id,order_no,amount,currency,requested_at,failed_at,observed_at) VALUES(?,?,?,?,10000,'KRW','2026-10-29 01:00:00','2026-10-29 01:00:01','2026-10-29 01:00:02')";
            legacy.update(insert, id(), pending.getPaymentId(), "tx-upgrade", pending.getOrderNo());
            assertThatThrownBy(() -> legacy.update(insert, id(), paid.getPaymentId(), "tx-upgrade", paid.getOrderNo())).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update(insert, id(), id(), "tx-other", pending.getOrderNo())).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update("UPDATE failed_payment_attempt SET observed_at='2026-10-29 00:00:00'")).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_failed_payment_attempt_values");
            flyway.migrate(); assertThat(legacy.queryForList("SELECT * FROM purchase_payment ORDER BY id")).isEqualTo(after);
        } finally { admin.execute("DROP DATABASE " + schema); }
    }

    private Payment received() { return receive.execute(request()); }
    private Payment stored(Payment value) { return payments.findByPaymentId(value.getPaymentId()).orElseThrow(); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String member() { return PaymentIntegrationTestBase.token("usr_buyer", "MEMBER"); }
    private String historyPath(Payment payment) { return "/api/v1/payments/" + payment.getOrderNo() + "/attempt-failures"; }
    private String checkout(Payment payment) { return "/api/v1/payments/" + payment.getOrderNo() + "/checkout"; }
    private void history(Payment payment, String... items) { HISTORIES.put(payment.getPaymentId(), "{\"items\":[" + String.join(",", items) + "]}"); }
    private String failed(Payment payment, String attempt, int tick) {
        Instant requested = payment.getCreatedAt().plusNanos(tick * 1000L); Instant failed = requested.plusNanos(1000);
        return """
                {"status":"FAILED","id":"%s","paymentId":"%s","storeId":"%s","version":"V2","currency":"KRW",
                 "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},"amount":{"total":10000,"paid":0,"discount":0,"cancelled":0},
                 "customData":"{\\"orderNo\\":\\"%s\\"}","requestedAt":"%s","failedAt":"%s","failure":{"pgMessage":"private supplier message"}}
                """.formatted(attempt, payment.getPaymentId(), STORE, CHANNEL, payment.getOrderNo(), requested, failed);
    }
    private String paid(Payment payment) {
        return """
                {"status":"PAID","id":"%s","transactionId":"successful-attempt","storeId":"%s","version":"V2","currency":"KRW",
                 "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},"amount":{"total":10000,"paid":10000,"discount":0,"cancelled":0},
                 "customData":"{\\"orderNo\\":\\"%s\\"}","paidAt":"%s","pgTxId":"pg-%s"}
                """.formatted(payment.getPaymentId(), STORE, CHANNEL, payment.getOrderNo(), payment.getCreatedAt().plusNanos(100000), payment.getPaymentId());
    }
    private void assertCompletion(Payment payment) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox_event WHERE aggregate_id=?", payment.getOrderNo());
        assertThat(row).containsEntry("event_type", "OrderPaymentCompleted").containsEntry("topic", "stove.payment.v1").containsEntry("partition_key", payment.getOrderNo());
        try {
            JsonNode body = mapper.readTree(row.get("payload").toString());
            assertThat(body.path("requestEventId").asText()).isEqualTo(payment.getRequestEventId()); assertThat(body.path("paymentId").asText()).isEqualTo(payment.getPaymentId());
            assertThat(body.path("orderNo").asText()).isEqualTo(payment.getOrderNo()); assertThat(body.path("buyerSubject").asText()).isEqualTo(payment.getBuyerSubject());
            assertThat(body.path("amount").asLong()).isEqualTo(payment.getAmount()); assertThat(body.path("currency").asText()).isEqualTo("KRW");
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderPaymentFailed'", Integer.class)).isZero();
    }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> value = new LinkedHashMap<>();
        for (String table : List.of("purchase_payment", "failed_payment_attempt", "processed_event", "outbox_event")) value.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return value;
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private Response get(String path) { return http("GET", path, member()); }
    private Response http(String method, String path, String token) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getFirst("Cache-Control"), mapper.readTree(reply.getBody())));
    }
    private record Response(int status, String cache, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
