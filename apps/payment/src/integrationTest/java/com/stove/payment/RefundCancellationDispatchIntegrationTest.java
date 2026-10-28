package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.testcontainers.SharedContainers;
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundCancellationClaim;
import com.stove.payment.core.domain.RefundCancellationHoldRepository;
import com.stove.payment.core.domain.RefundCancellationHold;
import com.stove.payment.core.domain.RefundCancellationResumeRequest;
import com.stove.payment.core.domain.RefundCancellationResumeReceipt;
import com.stove.payment.core.service.RequestRefundCancellationResume;
import com.stove.payment.core.service.ResolveRefundRetry;
import com.stove.payment.core.domain.PortOneWebhookNotice;
import com.stove.payment.adapter.pg.JdbcPortOneWebhookReceipts;
import com.stove.payment.core.service.ReceivePortOneWebhook;
import com.stove.common.web.error.BusinessException;
import java.util.ArrayList;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import com.stove.payment.core.service.RecordRefundPreparation;
import com.stove.payment.adapter.pg.PortOneRefundCancellationGateway;
import com.stove.payment.adapter.pg.PortOneRefundPaymentLookup;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.PgAuthenticationUnavailable;
import com.stove.payment.core.domain.PgPaymentNotFound;
import com.stove.payment.core.domain.PgCancellationNotFound;
import com.stove.payment.core.domain.PgPaymentLookupForbidden;
import com.stove.payment.core.service.ResolveRefundLookup;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.port.RefundCancellationGateway;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.ReconcileRefundPayment;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.stove.payment.core.service.RecordRefundCancellationSuccess;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.MySQLContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class RefundCancellationDispatchIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant BASE = Payment.now();
    @Autowired PaymentRepository payments;
    @Autowired ReceiveRefundExecution receive;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    @MockitoBean Clock clock;
    @MockitoSpyBean RefundExecutionRepository executions;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoSpyBean RefundCancellationHoldRepository holds;
    @MockitoSpyBean RequestRefundCancellationResume resume;
    private HttpServer pg;
    private RefundExecution current;
    private volatile Mode mode;
    private volatile AuthenticationGate authenticationGate;
    private volatile LookupState lookupState;
    private final AtomicInteger authenticationRejections = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final List<String> rejectedBodies = new CopyOnWriteArrayList<>();
    private final List<String> rejectedKeys = new CopyOnWriteArrayList<>();
    private volatile long cancelled;
    private final List<PgRefundCancellationReceipt> retryHistory = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final Set<String> completedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    private CountDownLatch responseReady = new CountDownLatch(1);
    private CountDownLatch releaseResponse = new CountDownLatch(1);
    private ExecutorService pgThreads;

    @BeforeEach void initialize() throws Exception {
        clear(); when(clock.instant()).thenReturn(BASE);
        mode = Mode.COMPLETE; authenticationGate = AuthenticationGate.NONE; lookupState = LookupState.AVAILABLE;
        responseReady = new CountDownLatch(1); releaseResponse = new CountDownLatch(1);
        pg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pgThreads = Executors.newFixedThreadPool(4); pg.setExecutor(pgThreads);
        pg.createContext("/payments/", exchange -> {
            try {
                boolean post = "POST".equals(exchange.getRequestMethod());
                if (!post) reads.incrementAndGet();
                boolean protectedCall = authenticationGate == AuthenticationGate.ALL
                        || authenticationGate == AuthenticationGate.POST_ONLY && post
                        || authenticationGate == AuthenticationGate.GET_AFTER_POST && !post && !bodies.isEmpty();
                if (protectedCall && !"PortOne corrected-fixture-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    authenticationRejections.incrementAndGet();
                    if (post) {
                        rejectedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        rejectedKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    }
                    byte[] error = "{\"type\":\"UNAUTHORIZED\",\"message\":\"fixture authentication rejected\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(401, error.length);
                    try (OutputStream output = exchange.getResponseBody()) { output.write(error); }
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())) {
                    if (lookupState == LookupState.FORBIDDEN || (lookupState == LookupState.FORBIDDEN_AFTER_POST && !bodies.isEmpty())) {
                        byte[] rejected = "<html>private-provider-detail</html>".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/html"); exchange.sendResponseHeaders(403, rejected.length);
                        try (OutputStream output = exchange.getResponseBody()) { output.write(rejected); }
                        return;
                    }
                    if (lookupState == LookupState.NOT_FOUND || (lookupState == LookupState.NOT_FOUND_AFTER_POST && !bodies.isEmpty())) {
                        byte[] missing = "{\"type\":\"PAYMENT_NOT_FOUND\",\"message\":\"fixture lookup missing\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(404, missing.length);
                        try (OutputStream output = exchange.getResponseBody()) { output.write(missing); }
                        return;
                    }
                    if ((mode == Mode.DIRECT_SUCCESS || mode == Mode.FAILED_LOOKUP_DOWN || mode == Mode.FORBIDDEN_LOOKUP_DOWN
                            || mode == Mode.INVALID_LOOKUP_DOWN) && !bodies.isEmpty()) {
                        exchange.sendResponseHeaders(503, -1); exchange.close(); return;
                    }
                    String observed = paymentBody();
                    if (lookupState == LookupState.WRONG_PURCHASE)
                        observed = observed.replace(current.getOrderNo(), "00000000-0000-0000-0000-000000000000");
                    byte[] bytes = observed.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                bodies.add(body); keys.add(key);
                RefundExecution committed = stored();
                assertThat(committed.getCancellationRequestBody()).isEqualTo(body);
                assertThat(committed.getFirstCancellationPreparedAt()).isNotNull();
                assertThat(key).isEqualTo("\"" + current.getAttemptId() + "\"");
                JsonNode json = new ObjectMapper().readTree(body);
                assertThat(json.path("amount").longValue()).isEqualTo(current.getAmount());
                assertThat(json.path("currentCancellableAmount").longValue()).isEqualTo(current.getAmount());
                int status = 200;
                String reply = "{\"cancellation\":{\"status\":\"SUCCEEDED\"}}";
                if (mode == Mode.INVALID_REQUEST || mode == Mode.INVALID_LOOKUP_DOWN || mode == Mode.DELAYED_INVALID) {
                    status = 400; reply = "{\"type\":\"INVALID_REQUEST\",\"message\":\"private-provider-detail\"}";
                    if (mode == Mode.DELAYED_INVALID) { responseReady.countDown();
                        if (!releaseResponse.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("invalid reply not released"); }
                }
                else if (mode == Mode.UNRECOGNIZED_BAD_REQUEST) { status = 400; reply = "{\"type\":\"OTHER_ERROR\"}"; }
                else if (mode == Mode.FORBIDDEN || mode == Mode.FORBIDDEN_LOOKUP_DOWN || mode == Mode.DELAYED_FORBIDDEN) {
                    status = 403; reply = "<html>private-provider-detail</html>";
                    if (mode == Mode.DELAYED_FORBIDDEN) { responseReady.countDown();
                        if (!releaseResponse.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("forbidden reply not released"); }
                }
                else if (mode == Mode.POST_NOT_FOUND) { status = 404; reply = "{\"type\":\"PAYMENT_NOT_FOUND\",\"message\":\"fixture cancellation target missing\"}"; }
                else if (mode == Mode.RACE) { cancelled = 3000; status = 409; reply = "{\"type\":\"CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN\"}"; }
                else if (mode.name().startsWith("RETRY_")) {
                    String state = mode == Mode.RETRY_FAILURE ? "FAILED" : "SUCCEEDED";
                    String cancellationId = "cancel-" + bodies.size();
                    if (mode == Mode.RETRY_FAILURE) {
                        Payment payment = payments.findByPaymentId(current.getPaymentId()).orElseThrow();
                        retryHistory.add(new PgRefundCancellationReceipt(new PgRefundLookupRequest(payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(),
                                "KRW", payment.getProviderTransactionId(), payment.getProviderOccurredAt()), current.getAttemptId(), cancellationId,
                                PgRefundCancellationReceipt.State.FAILED, clock.instant(), 0, 0, json.path("reason").textValue()));
                    } else { completedKeys.add(key); cancelled = current.getAmount(); }
                    reply = new ObjectMapper().writeValueAsString(Map.of("cancellation", Map.of("status", state, "id", cancellationId,
                            "totalAmount", current.getAmount(), "taxFreeAmount", 0, "vatAmount", 0,
                            "reason", json.path("reason").textValue(), "requestedAt", clock.instant().toString())));
                }
                else if (mode.name().startsWith("FAILED_") || mode == Mode.REQUESTED_THEN_FAILED) {
                    cancelled = mode == Mode.FAILED_MANUAL_FULL ? current.getAmount() : mode == Mode.FAILED_PARTIAL ? 3000 : 0;
                    reply = "{\"cancellation\":" + failureRow(mode == Mode.REQUESTED_THEN_FAILED ? "REQUESTED" : "FAILED") + "}";
                }
                else if (mode == Mode.UNAVAILABLE) { status = 503; reply = "{}"; }
                else {
                    completedKeys.add(key); cancelled = current.getAmount();
                    if (mode == Mode.COMPLETE_THEN_UNAVAILABLE) { status = 503; reply = "{}"; }
                    if (mode == Mode.DIRECT_SUCCESS || mode == Mode.DELAYED_SUCCESS) {
                        reply = new ObjectMapper().writeValueAsString(Map.of("cancellation", Map.of(
                                "status", "SUCCEEDED", "id", "cancel-1", "totalAmount", current.getAmount(),
                                "taxFreeAmount", 0, "vatAmount", 0, "reason", json.path("reason").textValue(), "requestedAt", BASE.toString())));
                        if (mode == Mode.DELAYED_SUCCESS) { responseReady.countDown();
                            if (!releaseResponse.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("reply fixture not released"); }
                    }
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (Throwable failure) { callbackFailure.set(failure); exchange.close(); }
        });
        pg.start();
    }

    @AfterEach void clear() {
        releaseResponse.countDown();
        if (pg != null) { pg.stop(0); pg = null; }
        if (pgThreads != null) { pgThreads.shutdownNow(); pgThreads = null; }
        reset(executions, outbox, holds, resume); retryHistory.clear(); bodies.clear(); keys.clear(); completedKeys.clear(); callbackFailure.set(null); cancelled = 0;
        reads.set(0);
        lookupState = LookupState.AVAILABLE;
        rejectedBodies.clear(); rejectedKeys.clear(); authenticationRejections.set(0); authenticationGate = AuthenticationGate.NONE;
        jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @Test void aClearFullBalanceUsesACommittedBodyAndCompletesOnlyThroughVerifiedLookup() {
        received(); PgResult original = payments.findByPaymentId(current.getPaymentId()).orElseThrow().supplierResult();
        service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(bodies).hasSize(1);
        assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getCancellationBalanceChangedAt()).isNull();
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().supplierResult()).isEqualTo(original);
        assertThat(resultEventCount()).isEqualTo(1);
        service(gateway()).execute(current.getAttemptId()); assertThat(bodies).hasSize(1);
    }

    @Test void priorPartialCancellationPersistsTheStopAndNeverPostsTheRemainingAmount() {
        received(); cancelled = 3000;
        service(gateway()).execute(current.getAttemptId());
        assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getRefundConfirmedAt()).isNull(); assertThat(bodies).isEmpty();
        when(clock.instant()).thenReturn(BASE.plusSeconds(30));
        service(gateway()).execute(current.getAttemptId());
        assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE); assertThat(bodies).isEmpty();
        cancelled = current.getAmount();
        service(gateway()).execute(current.getAttemptId());
        assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(30)); assertThat(bodies).isEmpty();
    }

    @Test void aPartialCancellationRacingWithThePostIsStoppedWithoutASecondSmallerRequest() {
        received(); mode = Mode.RACE;
        service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(bodies).hasSize(1);
        assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE);
        assertThat(stored().getRefundConfirmedAt()).isNull(); assertThat(resultEventCount()).isZero();
        service(gateway()).execute(current.getAttemptId()); assertThat(bodies).hasSize(1);
    }

    @Test void anUnknownPostOutcomeCanStillCompleteTheCustomerRefundByQuerying() {
        received(); mode = Mode.COMPLETE_THEN_UNAVAILABLE;
        service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(stored().getRefundConfirmedAt()).isNotNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void restartAndChangedBodyGenerationCannotReplaceTheFirstKeyBodyOrDeadline() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        String first = stored().getCancellationRequestBody(); Instant firstAt = stored().getFirstCancellationPreparedAt();
        PortOneRefundCancellationGateway changed = spy(gateway());
        doReturn(first.replace("ESD refund ", "Changed deployment reason ")).when(changed).prepareBody(current.request());
        mode = Mode.COMPLETE; when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        service(changed).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(bodies).containsExactly(first, first);
        assertThat(keys.stream().distinct().toList()).hasSize(1);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(firstAt);
    }

    @Test void anExpiredWindowAllowsOnlyLookupEvenAfterAnUnconfirmedPost() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10800));
        service(gateway()).execute(current.getAttemptId());
        assertThat(bodies).hasSize(1); assertThat(stored().getAutomaticCancellationStoppedAt()).isEqualTo(BASE.plusSeconds(10800));
        cancelled = current.getAmount();
        service(gateway()).execute(current.getAttemptId());
        assertThat(stored().getRefundConfirmedAt()).isNotNull(); assertThat(bodies).hasSize(1);
    }

    @Test void aPreparationFailureAfterRealFlushCannotSendAnExternalRequest() {
        received();
        doAnswer(call -> { entityManager.flush(); throw new IllegalStateException("after preparation flush"); }).when(executions).flush();
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getFirstCancellationPreparedAt()).isNull(); assertThat(stored().getCancellationRequestBody()).isNull();
        assertThat(bodies).isEmpty(); reset(executions);
        service(gateway()).execute(current.getAttemptId()); assertThat(bodies).hasSize(1); assertThat(callbackFailure.get()).isNull();
    }

    @Test void concurrentWorkersKeepOneKeyAndBodyAndPublishOneMoneyConfirmation() throws Exception {
        received(); CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> work = List.of(pool.submit(() -> { start.await(); service(gateway()).execute(current.getAttemptId()); return null; }),
                    pool.submit(() -> { start.await(); service(gateway()).execute(current.getAttemptId()); return null; }));
            start.countDown(); for (Future<?> future : work) future.get(20, TimeUnit.SECONDS);
            assertThat(callbackFailure.get()).isNull(); assertThat(bodies).isNotEmpty();
            assertThat(bodies.stream().distinct().toList()).hasSize(1); assertThat(keys.stream().distinct().toList()).hasSize(1);
            assertThat(completedKeys).hasSize(1); assertThat(resultEventCount()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void directSuccessIsCommittedEvenWhenTheFollowingLookupWouldBeUnavailable() {
        received(); mode = Mode.DIRECT_SUCCESS;
        service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_RESPONSE");
        assertThat(stored().getLookupSchedule().getNextAt()).isNull();
        assertThat(stored().getLookupSchedule().getClaimId()).isNull();
        assertThat(stored().getProviderRefundedAt()).isNull();
        assertThat(stored().getCancellationSuccessEventId()).isEqualTo(stored().getRefundConfirmationEventId());
        assertThat(resultEventCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'", String.class)).contains("PG_RESPONSE");
    }

    @Test void aDelayedSuccessPreservesTheAlreadyCommittedQueryConfirmation() throws Exception {
        received(); mode = Mode.DELAYED_SUCCESS;
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> work = caller.submit(() -> service(gateway()).execute(current.getAttemptId()));
            assertThat(responseReady.await(15, TimeUnit.SECONDS)).isTrue();
            reconcile().execute(current.getAttemptId());
            String original = stored().getRefundConfirmationEventId();
            assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
            releaseResponse.countDown(); work.get(15, TimeUnit.SECONDS);
            assertThat(callbackFailure.get()).isNull(); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
            assertThat(stored().getRefundConfirmationEventId()).isEqualTo(original);
            assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundPaymentConfirmed'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionSucceeded'", Integer.class)).isEqualTo(1);
        } finally { releaseResponse.countDown(); caller.shutdownNow(); }
    }

    @Test void successEvidenceAndItsOutboxRollBackTogetherAndTheSameReceiptCanResume() {
        received(); mode = Mode.DIRECT_SUCCESS;
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); if (call.getArgument(2,DomainEvent.class).eventType().equals("RefundExecutionPrepared")) return null;
            throw new IllegalStateException("after success outbox insert");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getRefundConfirmedAt()).isNull(); assertThat(stored().getCancellationSucceededAt()).isNull();
        assertThat(resultEventCount()).isZero(); assertThat(count("refund_cancellation_evidence")).isZero();
        reset(outbox); successRecorder().execute(current.getAttemptId(), successProof());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void concurrentReceiptRecordersKeepOneSuccessAndOneCompletionEvent() throws Exception {
        received();
        new RecordRefundCancellationPreparation(executions, transactions, clock, new RecordRefundPreparation(outbox)).execute(current.getAttemptId());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> executions.lockByAttemptId(current.getAttemptId()).orElseThrow()
                .freezeCancellationBody(gateway().prepareBody(current.request())));
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            PgRefundCancellationSuccess proof = successProof();
            List<Future<?>> work = List.of(pool.submit(() -> { start.await(); successRecorder().execute(current.getAttemptId(), proof); return null; }),
                    pool.submit(() -> { start.await(); successRecorder().execute(current.getAttemptId(), proof); return null; }));
            start.countDown(); for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
            assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED); assertThat(resultEventCount()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    private PgRefundCancellationSuccess successProof() {
        Payment payment = payments.findByPaymentId(current.getPaymentId()).orElseThrow();
        return new PgRefundCancellationSuccess(new PgRefundLookupRequest(payment.getPaymentId(), payment.getOrderNo(), payment.getAmount(),
                "KRW", payment.getProviderTransactionId(), payment.getProviderOccurredAt()), current.getAttemptId(),
                new RefundCancellation("cancel-1", current.getAmount(), null), BASE, 0, 0, "ESD refund " + current.getRefundId());
    }
    private RecordRefundCancellationSuccess successRecorder() {
        return new RecordRefundCancellationSuccess(payments, executions, outbox, transactions, clock);
    }
    private ReconcileRefundPayment reconcile() {
        return new ReconcileRefundPayment(payments, executions, new PortOneRefundPaymentLookup(client(), STORE, CHANNEL), outbox, transactions, clock);
    }

    private void received() {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW", "pg-original",
                BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        payments.saveAndFlush(paid); current = receive.execute(execution(paid));
    }
    @Test void aValidatedFailedResponseRequiresTheAdditionalUnrefundedLookupBeforePublishingOneFailure() {
        received(); mode = Mode.FAILED_ZERO; service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(bodies).hasSize(1);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.FAILED);
        assertThat(stored().getFailureLookupHash()).matches("[a-f0-9]{64}");
        assertThat(stored().getRefundConfirmedAt()).isNull(); assertThat(stored().getLookupSchedule().getNextAt()).isNull();
        assertThat(resultEventCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'", String.class)).isEqualTo("RefundExecutionFailed");
        service(gateway()).execute(current.getAttemptId()); reconcile().execute(current.getAttemptId());
        assertThat(bodies).hasSize(1); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void aFailedOwnRequestDoesNotRestoreAccessWhenAnotherCancellationAlreadyRefundedTheWholePurchase() {
        received(); mode = Mode.FAILED_MANUAL_FULL; service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(bodies).hasSize(1);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(stored().getConfirmedCancellations().getFirst().getCancellationId()).isEqualTo("cancel-manual");
        assertThat(stored().getFailureEventId()).isNull();
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'", String.class)).isEqualTo("RefundPaymentConfirmed");
    }

    @Test void aRequestedReceiptSurvivesRestartAndItsOwnFailedIdCanLaterBeVerified() {
        received(); mode = Mode.REQUESTED_THEN_FAILED; service(gateway()).execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(stored().getReceiptCancellationId()).isEqualTo("cancel-1");
        assertThat(resultEventCount()).isZero();
        mode = Mode.FAILED_ZERO; when(clock.instant()).thenReturn(BASE.plusSeconds(5));
        reconcile().execute(current.getAttemptId()); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.FAILED);
        assertThat(resultEventCount()).isEqualTo(1); assertThat(bodies).hasSize(1);
    }

    @Test void aFailedReplyWithAnUnavailableQueryOrOtherPendingCancellationCannotAuthorizeRestoration() {
        received(); mode = Mode.FAILED_LOOKUP_DOWN;
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        assertThat(stored().getReceiptState().name()).isEqualTo("FAILED"); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        mode = Mode.FAILED_OTHER_PENDING; reconcile().execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
        mode = Mode.FAILED_PARTIAL; cancelled = 3000; reconcile().execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
    }

    @Test void verifiedFailureAndOutboxRollbackTogetherThenResumeFromThePersistedReceipt() {
        received(); mode = Mode.FAILED_ZERO;
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); if (call.getArgument(2,DomainEvent.class).eventType().equals("RefundExecutionPrepared")) return null;
            executions.flush(); throw new IllegalStateException("after failure flush and outbox insert");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(stored().getFailureEventId()).isNull();
        assertThat(stored().getReceiptState().name()).isEqualTo("FAILED"); assertThat(resultEventCount()).isZero();
        reset(outbox); reconcile().execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.FAILED); assertThat(resultEventCount()).isEqualTo(1); assertThat(bodies).hasSize(1);
    }

    @Test void twoVerifiedFailuresCanLeadToAnExplicitThirdAttemptWithANewKeyAndOneRefund() {
        received(); mode = Mode.FAILED_ZERO; service(gateway()).execute(current.getAttemptId()); RefundExecution first = stored();
        String firstFailure = first.getFailureEventId(); rememberFailure(first);
        current = receiveNext(first); mode = Mode.RETRY_FAILURE; service(gateway()).execute(current.getAttemptId()); RefundExecution second = stored();
        assertThat(second.getStatus()).isEqualTo(RefundExecutionStatus.FAILED); assertThat(second.getPreviousAttemptId()).isEqualTo(first.getAttemptId());
        assertThatThrownBy(() -> receive.execute(nextRequest(first))).isInstanceOf(BusinessException.class);
        current = receiveNext(second); mode = Mode.RETRY_SUCCESS; service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(bodies).hasSize(3); assertThat(keys.stream().distinct().toList()).hasSize(3); assertThat(completedKeys).hasSize(1);
        assertThat(executions.count()).isEqualTo(3); assertThat(resultEventCount()).isEqualTo(3);
        RefundExecution retained = executions.findByAttemptId(first.getAttemptId()).orElseThrow();
        assertThat(retained.getFailureEventId()).isEqualTo(firstFailure); assertThat(retained.getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(second.getFirstCancellationPreparedAt()).isEqualTo(BASE.plusSeconds(86400));
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE.plusSeconds(172800));
        service(gateway()).execute(current.getAttemptId()); assertThat(bodies).hasSize(3);
    }

    @Test void anotherPendingCancellationBlocksTheRetryPostEvenAfterTheOldFailureWasVerified() {
        received(); mode = Mode.FAILED_ZERO; service(gateway()).execute(current.getAttemptId()); RefundExecution first = stored(); rememberFailure(first);
        current = receiveNext(first); mode = Mode.RETRY_OTHER_PENDING; service(gateway()).execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(stored().getReceiptCancellationId()).isNull();
        assertThat(bodies).hasSize(1); assertThat(resultEventCount()).isEqualTo(1); assertThat(callbackFailure.get()).isNull();
    }

    @Test void concurrentSuccessorDeliveriesCommitOneReceiptAndLeaveTheOriginalFailedTraceUntouched() throws Exception {
        received(); mode = Mode.FAILED_ZERO; service(gateway()).execute(current.getAttemptId()); RefundExecution first = stored();
        when(clock.instant()).thenReturn(BASE.plusSeconds(86400)); RefundExecutionRequest next = nextRequest(first);
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RefundExecution> left = pool.submit(() -> { start.await(); return receive.execute(next); });
            Future<RefundExecution> right = pool.submit(() -> { start.await(); return receive.execute(next); });
            start.countDown(); assertThat(left.get(15, TimeUnit.SECONDS).getAttemptId()).isEqualTo(right.get(15, TimeUnit.SECONDS).getAttemptId());
            assertThat(executions.count()).isEqualTo(2); assertThat(count("refund_execution_trace")).isEqualTo(2);
            assertThat(executions.findByAttemptId(first.getAttemptId()).orElseThrow().getFailureEventId()).isEqualTo(first.getFailureEventId());
            assertThat(bodies).hasSize(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void aCancellationWebhookWakesTheCurrentPendingSuccessorRatherThanTheOldFailedExecution() {
        received(); mode = Mode.FAILED_ZERO; service(gateway()).execute(current.getAttemptId()); RefundExecution first = stored();
        current = receiveNext(first); new RecordRefundCancellationPreparation(executions, transactions, clock, new RecordRefundPreparation(outbox)).execute(current.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(86401));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> new ReceivePortOneWebhook(payments, new JdbcPortOneWebhookReceipts(jdbc), clock, executions)
                .execute(new PortOneWebhookNotice("msg_successor", "a".repeat(64), "Transaction.CancelPending", STORE, current.getPaymentId(), "pg-original", BASE.plusSeconds(86401))));
        assertThat(stored().getLookupSchedule().getWakeAt()).isEqualTo(BASE.plusSeconds(86401));
        assertThat(executions.findByAttemptId(first.getAttemptId()).orElseThrow().getLookupSchedule().getWakeAt()).isNull(); assertThat(bodies).hasSize(1);
    }

    @Test void automaticRetriesKeepTheOriginalBodyKeyAndWindowAcrossTenThirtySixtySecondCycles() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        when(clock.instant()).thenReturn(BASE.plusSeconds(9)); retryWorker().execute(); assertThat(bodies).hasSize(1);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40));
        when(clock.instant()).thenReturn(BASE.plusSeconds(40)); retryWorker().execute();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(100));
        when(clock.instant()).thenReturn(BASE.plusSeconds(100)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).hasSize(4); assertThat(bodies.stream().distinct().toList()).hasSize(1);
        assertThat(keys.stream().distinct().toList()).containsExactly("\"" + current.getAttemptId() + "\"");
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(completedKeys).hasSize(1);
    }

    @Test void anOldPreparationWithABodyIsObservedThenWaitsTenSecondsBeforeResuming() {
        received();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            RefundExecution execution = executions.lockByAttemptId(current.getAttemptId()).orElseThrow();
            execution.prepareCancellation(BASE); execution.freezeCancellationBody(gateway().prepareBody(execution.request()));
        });
        retryWorker().execute(); assertThat(bodies).isEmpty();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getCancellationSchedule().getLastFinishedAt()).isNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); mode = Mode.DIRECT_SUCCESS; retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
    }

    @Test void aPartialBalanceStopsRetryCommandsAndPreservesResultLookup() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        cancelled = 3000; when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
    }

    @Test void unexpectedPendingCancellationHistoryDefersWithoutSendingAnotherPost() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        mode = Mode.FAILED_OTHER_PENDING; when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40));
    }

    @Test void aFailedPreflightReadCannotSendAndTheNextCycleWaitsThirtySeconds() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        mode = Mode.FAILED_LOOKUP_DOWN; when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isNull();
        assertThat(stored().getFailureEventId()).isNull();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40));
    }

    @Test void aRecordedRequestedReceiptDisablesPostRetryAndLeavesFailureVerificationToGet() {
        received(); mode = Mode.REQUESTED_THEN_FAILED; service(gateway()).executeFirst(current.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(60)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getReceiptState()).isEqualTo(PgRefundCancellationReceipt.State.REQUESTED);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
    }

    @Test void expiredFirstOwnerRecoversOnlyAfterItsLeaseAndDelayAndCannotOverwriteTheSuccessor() {
        received(); AtomicReference<RefundCancellationClaim> old = new AtomicReference<>();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            RefundExecution execution = executions.lockByAttemptId(current.getAttemptId()).orElseThrow();
            execution.prepareCancellation(BASE); execution.freezeCancellationBody(gateway().prepareBody(execution.request()));
            old.set(execution.getCancellationSchedule().claim(id(), BASE, Duration.ofMinutes(4), false));
        });
        when(clock.instant()).thenReturn(BASE.plusSeconds(249)); retryWorker().execute(); assertThat(bodies).isEmpty();
        when(clock.instant()).thenReturn(BASE.plusSeconds(250)); mode = Mode.UNAVAILABLE; retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(280));
        service(gateway()).finishRetry(current.getAttemptId(), old.get());
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(280));
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
    }

    @Test void aFullExecutorReleasesItsDurableClaimWithoutAdvancingTheRetryStage() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        new ResolveRefundRetry(executions, service(gateway()), clock, task -> { throw new RejectedExecutionException("full"); }, 20).execute();
        assertThat(stored().getCancellationSchedule().getClaimId()).isNull();
        assertThat(stored().getCancellationSchedule().getStage()).isEqualTo(1);
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        mode = Mode.RETRY_SUCCESS; retryWorker().execute(); assertThat(bodies).hasSize(2);
        assertThat(stored().getRefundConfirmedAt()).isNotNull();
    }

    @Test void twoScannersCannotPostTheSameDueAttemptWhileItsHttpCallIsInProgress() throws Exception {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); mode = Mode.DELAYED_SUCCESS;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> retryWorker().execute());
            assertThat(responseReady.await(15, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> retryWorker().execute()); second.get(10, TimeUnit.SECONDS);
            assertThat(bodies).hasSize(2); assertThat(stored().getCancellationSchedule().getClaimId()).isNotNull();
            releaseResponse.countDown(); first.get(15, TimeUnit.SECONDS);
            assertThat(stored().getRefundConfirmedAt()).isNotNull(); assertThat(resultEventCount()).isEqualTo(1);
        } finally { releaseResponse.countDown(); pool.shutdownNow(); }
    }

    @Test void theThreeHourCutoffRemovesRetryDeadlinesButKeepsTheOriginalExecutionAndGetSchedule() {
        received(); mode = Mode.UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10800)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getAutomaticCancellationStoppedAt()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
    }

    @Test void correctedCredentialsResumeTheSameRefundAfterUnauthorizedPreflightWithoutAnOperatorRelease() {
        received(); authenticationGate = AuthenticationGate.ALL;
        assertThatThrownBy(() -> authenticatedService("wrong-fixture-secret").executeFirst(current.getAttemptId()))
                .isInstanceOf(PgAuthenticationUnavailable.class);
        String originalBody = stored().getCancellationRequestBody();
        assertThat(authenticationRejections.get()).isEqualTo(2); assertThat(bodies).isEmpty();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        when(clock.instant()).thenReturn(BASE.plusSeconds(600)); mode = Mode.RETRY_SUCCESS;
        authenticatedWorker("corrected-fixture-secret").execute();
        assertThat(bodies).containsExactly(originalBody);
        assertThat(keys).containsExactly("\"" + current.getAttemptId() + "\"");
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(executions.count()).isEqualTo(1); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test void unauthorizedPostThenCorrectedCredentialsReuseTheExactFrozenBodyAndKey() {
        received(); authenticationGate = AuthenticationGate.POST_ONLY;
        assertThatThrownBy(() -> authenticatedService("wrong-fixture-secret").executeFirst(current.getAttemptId()))
                .isInstanceOf(PgAuthenticationUnavailable.class);
        assertThat(rejectedBodies).hasSize(1); assertThat(bodies).isEmpty(); assertThat(stored().getFailureEventId()).isNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); mode = Mode.RETRY_SUCCESS;
        authenticatedWorker("corrected-fixture-secret").execute();
        assertThat(bodies).containsExactlyElementsOf(rejectedBodies); assertThat(keys).containsExactlyElementsOf(rejectedKeys);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(completedKeys).hasSize(1); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void correctedLookupCredentialsDiscoverAnAlreadyCompletedRefundWithoutPostingAgain() {
        received(); authenticationGate = AuthenticationGate.GET_AFTER_POST; mode = Mode.COMPLETE_THEN_UNAVAILABLE;
        assertThatThrownBy(() -> authenticatedService("wrong-fixture-secret").executeFirst(current.getAttemptId()))
                .isInstanceOf(PgAuthenticationUnavailable.class);
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        authenticatedWorker("corrected-fixture-secret").execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
    }

    @Test void repairingAuthenticationAfterThreeHoursDoesNotRenewTheAutomaticPostWindow() {
        received(); authenticationGate = AuthenticationGate.ALL;
        assertThatThrownBy(() -> authenticatedService("wrong-fixture-secret").executeFirst(current.getAttemptId()))
                .isInstanceOf(PgAuthenticationUnavailable.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10800)); authenticatedWorker("corrected-fixture-secret").execute();
        authenticatedService("corrected-fixture-secret").executeFirst(current.getAttemptId());
        assertThat(bodies).isEmpty(); assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getAutomaticCancellationStoppedAt()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
    }

    @Test void restoredAuthenticationCannotBypassAPartialCancellationFoundInTheNewPreflight() {
        received(); authenticationGate = AuthenticationGate.ALL;
        assertThatThrownBy(() -> authenticatedService("wrong-fixture-secret").executeFirst(current.getAttemptId()))
                .isInstanceOf(PgAuthenticationUnavailable.class);
        cancelled = 3000; when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        authenticatedWorker("corrected-fixture-secret").execute();
        assertThat(bodies).isEmpty(); assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
    }

    @Test void forbiddenPostPersistsAHoldAcrossRecreatedSendersWhileGetContinues() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold();
        assertThat(hold.isActivated()).isTrue(); assertThat(hold.getReason()).isEqualTo("PG_REQUEST_FORBIDDEN");
        assertThat(hold.getObservedAt()).isEqualTo(BASE); assertThat(holds.count()).isEqualTo(1);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
        int before = reads.get();
        when(clock.instant()).thenReturn(BASE.plusSeconds(60)); mode = Mode.RETRY_SUCCESS;
        retryWorker().execute(); service(gateway()).executeFirst(current.getAttemptId()); service(gateway()).execute(current.getAttemptId());
        assertThat(reads.get()).isGreaterThan(before); assertThat(bodies).hasSize(1);
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(hold.getHoldId());
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
    }

    @Test void explicitOperatorResumeKeepsOneCommandReceiptAndTheOriginalPostIdentity() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold(); RefundCancellationResumeRequest request = resumeCommand(hold);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); RefundCancellationResumeReceipt first = resume.execute(request);
        when(clock.instant()).thenReturn(BASE.plusSeconds(20)); assertThat(resume.execute(request)).isEqualTo(first);
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).hasSize(2); assertThat(bodies.stream().distinct().toList()).hasSize(1);
        assertThat(keys.stream().distinct().toList()).containsExactly("\"" + current.getAttemptId() + "\"");
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        RefundCancellationHold saved = holds.findByHoldId(hold.getHoldId()).orElseThrow();
        assertThat(saved.getResumeRequestId()).isEqualTo(request.requestId()); assertThat(saved.getResumedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(saved.getOperatorSubject()).isEqualTo(request.operatorSubject()); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(resume.execute(request)).isEqualTo(first);
    }

    @Test void administratorHttpResumeReusesTheFrozenPgRequestAndAReplayCannotSendAgain() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold(); String originalBody = bodies.getFirst(); String originalKey = keys.getFirst();
        String path = "/api/v1/admin/refund-cancellation-holds/" + hold.getHoldId();
        Map<String, String> command = Map.of("requestId", id(), "attemptId", current.getAttemptId(), "note", "PG 설정 확인");
        when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        JsonNode first = resumeHttp(path, command);
        assertThat(first.path("data").path("holdId").asText()).isEqualTo(hold.getHoldId());
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).containsExactly(originalBody, originalBody); assertThat(keys).containsExactly(originalKey, originalKey);
        assertThat(stored().getRefundConfirmedAt()).isNotNull();
        assertThat(resumeHttp(path, command)).isEqualTo(first); retryWorker().execute(); assertThat(bodies).hasSize(2);
        assertThat(holds.findByHoldId(hold.getHoldId()).orElseThrow().getOperatorSubject()).isEqualTo("usr_http_admin");
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    private JsonNode resumeHttp(String path, Map<String, String> command) {
        return RestClient.create("http://127.0.0.1:" + port).post().uri(path + "/resume").header("Authorization", "Bearer " + token("usr_http_admin", "ADMIN"))
                .body(command).exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    return mapper.readTree(response.getBody());
                });
    }

    @Test void anOldResumeCommandCannotReleaseANewForbiddenPostHold() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold first = currentHold(); RefundCancellationResumeRequest original = resumeCommand(first);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); RefundCancellationResumeReceipt receipt = resume.execute(original); retryWorker().execute();
        RefundCancellationHold second = currentHold(); assertThat(second.getHoldId()).isNotEqualTo(first.getHoldId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(20)); assertThat(resume.execute(original)).isEqualTo(receipt);
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(second.getHoldId()); assertThat(bodies).hasSize(2);
        assertThatThrownBy(() -> resume.execute(new RefundCancellationResumeRequest(original.requestId(), original.attemptId(), original.holdId(),
                original.operatorSubject(), "changed review"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> resume.execute(resumeCommand(first))).isInstanceOf(BusinessException.class);
        resume.execute(resumeCommand(second)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).hasSize(3); assertThat(holds.count()).isEqualTo(2);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void concurrentIdenticalResumeCommandsReleaseOnlyTheSameHoldOnce() throws Exception {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationResumeRequest request = resumeCommand(currentHold()); when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RefundCancellationResumeReceipt> first = pool.submit(() -> { start.await(); return resume.execute(request); });
            Future<RefundCancellationResumeReceipt> second = pool.submit(() -> { start.await(); return resume.execute(request); });
            start.countDown(); assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
            assertThat(holds.count()).isEqualTo(1); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
            assertThat(bodies).hasSize(2); assertThat(resultEventCount()).isEqualTo(1);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void moneyConfirmedByGetBeforeOperatorResumeCannotBeCancelledAgain() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold(); cancelled = current.getAmount(); when(clock.instant()).thenReturn(BASE.plusSeconds(5));
        reconcile().execute(current.getAttemptId());
        assertThat(stored().getRefundConfirmedAt()).isNotNull();
        assertThatThrownBy(() -> resume.execute(resumeCommand(hold))).isInstanceOf(BusinessException.class);
        assertThat(holds.findByHoldId(hold.getHoldId()).orElseThrow().getResumeRequestId()).isNull();
        retryWorker().execute(); assertThat(bodies).hasSize(1); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void operatorResumeCannotResetTheThreeHourWindow() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold(); when(clock.instant()).thenReturn(BASE.plusSeconds(10800));
        assertThatThrownBy(() -> resume.execute(resumeCommand(hold))).isInstanceOf(BusinessException.class);
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(hold.getHoldId());
        assertThat(holds.findByHoldId(hold.getHoldId()).orElseThrow().getResumedAt()).isNull();
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE); assertThat(bodies).hasSize(1);
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
    }

    @Test void aResumedExecutionStillStopsAtANewPartialBalanceBeforePosting() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); resume.execute(resumeCommand(currentHold()));
        cancelled = 3000; mode = Mode.COMPLETE; retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getCancellationBalanceChangedAt()).isNotNull();
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
    }

    @Test void aHoldInsertFailureRollsBackTheHoldAndPauseAndTheSameExecutionCanRecover() {
        received(); mode = Mode.FORBIDDEN;
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            RefundCancellationHold inserted = call.getArgument(0);
            entityManager.persist(inserted); entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_cancellation_hold", Integer.class)).isEqualTo(1);
            throw new IllegalStateException("after hold insert flush");
        }).when(holds).saveAndFlush(any(RefundCancellationHold.class)));
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId()))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("after hold insert flush");
        assertThat(holds.count()).isZero(); assertThat(stored().getActiveCancellationHoldId()).isNull();
        reset(holds); when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(holds.count()).isEqualTo(1); assertThat(stored().getActiveCancellationHoldId()).isNotNull();
        assertThat(bodies).hasSize(2); assertThat(resultEventCount()).isZero();
    }

    @Test void aFailureAfterResumeFlushRollsBackBothTheAuditAndTheReleasedSchedule() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold(); RefundCancellationResumeRequest command = resumeCommand(hold);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); throw new IllegalStateException("after resume flush");
        }).when(resume).execute(any(RefundCancellationResumeRequest.class)));
        assertThatThrownBy(() -> resume.execute(command)).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(hold.getHoldId());
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(holds.findByHoldId(hold.getHoldId()).orElseThrow().getResumeRequestId()).isNull();
        reset(resume); resume.execute(command); assertThat(stored().getActiveCancellationHoldId()).isNull();
        assertThat(bodies).hasSize(1);
    }

    @Test void aLateForbiddenResponseFromAnExpiredOwnerCannotReopenACompletedRefund() throws Exception {
        received(); mode = Mode.DELAYED_FORBIDDEN; ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> old = pool.submit(() -> service(gateway()).executeFirst(current.getAttemptId()));
            assertThat(responseReady.await(15, TimeUnit.SECONDS)).isTrue();
            when(clock.instant()).thenReturn(BASE.plusSeconds(250)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
            assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
            releaseResponse.countDown(); old.get(15, TimeUnit.SECONDS);
            assertThat(holds.count()).isEqualTo(1); assertThat(holds.findAll().getFirst().isActivated()).isFalse();
            assertThat(stored().getActiveCancellationHoldId()).isNull(); assertThat(bodies).hasSize(2);
            assertThat(resultEventCount()).isEqualTo(1);
        } finally { releaseResponse.countDown(); pool.shutdownNow(); }
    }

    @Test void aGetOutageAfterForbiddenPostCannotLoseTheAlreadyCommittedHold() {
        received(); mode = Mode.FORBIDDEN_LOOKUP_DOWN;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        assertThat(holds.count()).isEqualTo(1); String holdId = stored().getActiveCancellationHoldId();
        assertThat(holdId).isNotNull(); assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(60)); mode = Mode.RETRY_SUCCESS;
        retryWorker().execute(); service(gateway()).executeFirst(current.getAttemptId());
        assertThat(bodies).hasSize(1); assertThat(stored().getActiveCancellationHoldId()).isEqualTo(holdId);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
    }

    @Test void missingPreflightRemainsPendingThenResumesTheOriginalRequestWhenTheSamePurchaseIsFound() {
        received(); lookupState = LookupState.NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentNotFound.class);
        String frozen = stored().getCancellationRequestBody();
        assertThat(bodies).isEmpty(); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getRefundConfirmedAt()).isNull();
        assertThat(stored().getActiveCancellationHoldId()).isNull(); assertThat(holds.count()).isZero();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        when(clock.instant()).thenReturn(BASE.plusSeconds(600)); lookupState = LookupState.AVAILABLE; mode = Mode.RETRY_SUCCESS;
        retryWorker().execute();
        assertThat(bodies).containsExactly(frozen); assertThat(keys).containsExactly("\"" + current.getAttemptId() + "\"");
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(executions.count()).isEqualTo(1); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void restoredLookupCanDiscoverAPreviouslyCompletedRefundWithoutSendingAnotherPost() {
        received(); lookupState = LookupState.NOT_FOUND_AFTER_POST; mode = Mode.COMPLETE_THEN_UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentNotFound.class);
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); lookupState = LookupState.AVAILABLE; retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(holds.count()).isZero();
    }

    @Test void findingThePurchaseAfterThreeHoursCannotRenewTheAutomaticPostWindow() {
        received(); lookupState = LookupState.NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentNotFound.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10800)); lookupState = LookupState.AVAILABLE;
        retryWorker().execute(); service(gateway()).executeFirst(current.getAttemptId());
        assertThat(bodies).isEmpty(); assertThat(stored().getAutomaticCancellationStoppedAt()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
    }

    @Test void aSuccessfulHttpReadOfADifferentPurchaseCannotAuthorizeTheResumedPost() {
        received(); lookupState = LookupState.NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentNotFound.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); lookupState = LookupState.WRONG_PURCHASE;
        retryWorker().execute();
        assertThat(bodies).isEmpty(); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40));
        assertThat(stored().getFailureEventId()).isNull(); assertThat(holds.count()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(40)); lookupState = LookupState.AVAILABLE; mode = Mode.RETRY_SUCCESS;
        retryWorker().execute(); assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isNotNull();
    }

    @Test void restoredLookupOfAPartialCancellationStillBlocksTheFullRefundPost() {
        received(); lookupState = LookupState.NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentNotFound.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); lookupState = LookupState.AVAILABLE; cancelled = 3000;
        retryWorker().execute();
        assertThat(bodies).isEmpty(); assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
    }

    @Test void recoveringA404LookupDoesNotReleaseAnExistingForbiddenPostHold() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        String holdId = stored().getActiveCancellationHoldId();
        lookupState = LookupState.NOT_FOUND; when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentNotFound.class);
        lookupState = LookupState.AVAILABLE; mode = Mode.RETRY_SUCCESS; when(clock.instant()).thenReturn(BASE.plusSeconds(20));
        retryWorker().execute(); service(gateway()).executeFirst(current.getAttemptId());
        assertThat(bodies).hasSize(1); assertThat(stored().getActiveCancellationHoldId()).isEqualTo(holdId);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(holds.count()).isEqualTo(1);
        assertThat(holds.findByHoldId(holdId).orElseThrow().getResumeRequestId()).isNull();
        assertThat(resultEventCount()).isZero();
    }

    @Test void repeatedMissingCancellationTargetsKeepTheSameRequestAndBackoffUntilAValidRetrySucceeds() {
        received(); mode = Mode.POST_NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgCancellationNotFound.class);
        String frozen = stored().getCancellationRequestBody();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getRefundConfirmedAt()).isNull();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        when(clock.instant()).thenReturn(BASE.plusSeconds(9)); retryWorker().execute(); assertThat(bodies).hasSize(1);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40));
        when(clock.instant()).thenReturn(BASE.plusSeconds(40)); retryWorker().execute();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(100));
        assertThat(stored().getActiveCancellationHoldId()).isNull(); assertThat(holds.count()).isZero();
        assertThat(resultEventCount()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(100)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).containsExactly(frozen, frozen, frozen, frozen);
        assertThat(keys).containsExactlyElementsOf(Collections.nCopies(4, "\"" + current.getAttemptId() + "\""));
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(executions.count()).isEqualTo(1); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test void aFullCancellationDiscoveredAfterPost404CompletesWithoutAnotherPost() {
        received(); mode = Mode.POST_NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgCancellationNotFound.class);
        cancelled = current.getAmount(); when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(resultEventCount()).isEqualTo(1); assertThat(holds.count()).isZero();
    }

    @Test void aPartialCancellationAfterPost404StillStopsAutomaticFullCancellation() {
        received(); mode = Mode.POST_NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgCancellationNotFound.class);
        cancelled = 3000; when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getRefundConfirmedAt()).isNull();
        assertThat(resultEventCount()).isZero();
    }

    @Test void aDifferentPurchaseAfterPost404CannotAuthorizeTheRetryButTheOriginalPurchaseCan() {
        received(); mode = Mode.POST_NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgCancellationNotFound.class);
        String frozen = stored().getCancellationRequestBody();
        lookupState = LookupState.WRONG_PURCHASE; when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getFailureEventId()).isNull();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40));
        lookupState = LookupState.AVAILABLE; mode = Mode.RETRY_SUCCESS;
        when(clock.instant()).thenReturn(BASE.plusSeconds(40)); retryWorker().execute();
        assertThat(bodies).containsExactly(frozen, frozen); assertThat(Set.copyOf(keys)).hasSize(1);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
    }

    @Test void post404CannotRenewTheOriginalThreeHourWindowAndLookupStillContinues() {
        received(); mode = Mode.POST_NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgCancellationNotFound.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10800)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getAutomaticCancellationStoppedAt()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE); assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        int previousReads = reads.get(); reconcile().execute(current.getAttemptId()); assertThat(reads.get()).isGreaterThan(previousReads);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
    }

    @Test void aLaterForbiddenResponseAfterPost404StillRequiresExplicitOperatorResume() {
        received(); mode = Mode.POST_NOT_FOUND;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgCancellationNotFound.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); mode = Mode.FORBIDDEN; retryWorker().execute();
        String holdId = stored().getActiveCancellationHoldId(); assertThat(holdId).isNotNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(40)); mode = Mode.RETRY_SUCCESS;
        retryWorker().execute(); service(gateway()).executeFirst(current.getAttemptId());
        assertThat(bodies).hasSize(2); assertThat(stored().getActiveCancellationHoldId()).isEqualTo(holdId);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(holds.count()).isEqualTo(1);
        assertThat(currentHold().getResumeRequestId()).isNull(); assertThat(resultEventCount()).isZero();
    }

    @Test void forbiddenPreflightDoesNotHoldOrFailTheRefundAndValidRecoveryResumesTheSameRequest() {
        received(); lookupState = LookupState.FORBIDDEN;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentLookupForbidden.class);
        String frozen = stored().getCancellationRequestBody();
        assertThat(bodies).isEmpty(); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getRefundConfirmedAt()).isNull();
        assertThat(stored().getActiveCancellationHoldId()).isNull(); assertThat(holds.count()).isZero();
        assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(bodies).isEmpty(); assertThat(stored().getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40));
        assertThat(resultEventCount()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(40)); lookupState = LookupState.AVAILABLE; mode = Mode.RETRY_SUCCESS;
        retryWorker().execute();
        assertThat(bodies).containsExactly(frozen); assertThat(keys).containsExactly("\"" + current.getAttemptId() + "\"");
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(executions.count()).isEqualTo(1); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test void forbiddenResultLookupKeepsItsScheduleAndLaterConfirmsTheRefundWithoutAnotherPost() {
        received(); lookupState = LookupState.FORBIDDEN_AFTER_POST; mode = Mode.COMPLETE_THEN_UNAVAILABLE;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentLookupForbidden.class);
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(5)); lookupWorker().execute();
        assertThat(stored().getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(15));
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
        assertThat(stored().getActiveCancellationHoldId()).isNull();
        when(clock.instant()).thenReturn(BASE.plusSeconds(15)); lookupState = LookupState.AVAILABLE; lookupWorker().execute();
        retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(15));
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(stored().getLookupSchedule().getNextAt()).isNull(); assertThat(stored().getCancellationSchedule().getNextAt()).isNull();
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test void aFailedReceiptWithForbiddenLookupCannotRequestRestorationUntilTheUnrefundedPurchaseIsVerified() {
        received(); lookupState = LookupState.FORBIDDEN_AFTER_POST; mode = Mode.FAILED_ZERO;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentLookupForbidden.class);
        assertThat(stored().getReceiptState().name()).isEqualTo("FAILED");
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getFailureLookupHash()).isNull();
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(resultEventCount()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(5)); lookupWorker().execute(); retryWorker().execute();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(15)); lookupState = LookupState.AVAILABLE; lookupWorker().execute();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.FAILED);
        assertThat(stored().getFailureLookupHash()).matches("[a-f0-9]{64}");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'", String.class)).isEqualTo("RefundExecutionFailed");
        lookupWorker().execute(); retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(resultEventCount()).isEqualTo(1); assertThat(holds.count()).isZero();
    }

    @Test void recoveringForbiddenLookupAfterThreeHoursCannotRestartAutomaticCancellation() {
        received(); lookupState = LookupState.FORBIDDEN;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentLookupForbidden.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10800)); lookupState = LookupState.AVAILABLE;
        retryWorker().execute();
        assertThat(bodies).isEmpty(); assertThat(stored().getAutomaticCancellationStoppedAt()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        int previousReads = reads.get(); lookupWorker().execute(); assertThat(reads.get()).isGreaterThan(previousReads);
        assertThat(stored().getLookupSchedule().getNextAt()).isAfter(BASE.plusSeconds(10800));
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
    }

    @Test void partialCancellationAfterForbiddenLookupStillBlocksAutomaticFullCancellation() {
        received(); lookupState = LookupState.FORBIDDEN;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentLookupForbidden.class);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); lookupState = LookupState.AVAILABLE; cancelled = 3000;
        retryWorker().execute();
        assertThat(bodies).isEmpty(); assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(stored().getFailureEventId()).isNull(); assertThat(stored().getRefundConfirmedAt()).isNull();
        assertThat(resultEventCount()).isZero(); assertThat(holds.count()).isZero();
    }

    @Test void successfulLookupOfAnotherPurchaseAfter403CannotAuthorizeTheRetry() {
        received(); lookupState = LookupState.FORBIDDEN;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgPaymentLookupForbidden.class);
        String frozen = stored().getCancellationRequestBody();
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); lookupState = LookupState.WRONG_PURCHASE; retryWorker().execute();
        assertThat(bodies).isEmpty(); assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(40)); lookupState = LookupState.AVAILABLE; mode = Mode.RETRY_SUCCESS;
        retryWorker().execute(); assertThat(bodies).containsExactly(frozen);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
    }

    @Test void recoveringForbiddenLookupDoesNotReleaseAnExistingForbiddenCancellationHold() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        String holdId = stored().getActiveCancellationHoldId();
        when(clock.instant()).thenReturn(BASE.plusSeconds(5)); lookupState = LookupState.FORBIDDEN; lookupWorker().execute();
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(holdId);
        when(clock.instant()).thenReturn(BASE.plusSeconds(15)); lookupState = LookupState.AVAILABLE; mode = Mode.RETRY_SUCCESS;
        lookupWorker().execute(); retryWorker().execute(); service(gateway()).executeFirst(current.getAttemptId());
        assertThat(bodies).hasSize(1); assertThat(stored().getActiveCancellationHoldId()).isEqualTo(holdId);
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(holds.count()).isEqualTo(1);
        assertThat(currentHold().getResumeRequestId()).isNull(); assertThat(resultEventCount()).isZero();
    }

    @Test void invalidRequestStopsAcrossRecreatedSendersAndLookupContinues() {
        received(); mode = Mode.INVALID_REQUEST; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold();
        assertThat(hold.getReason()).isEqualTo("PG_REQUEST_INVALID"); assertThat(hold.isActivated()).isTrue();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(60)); mode = Mode.RETRY_SUCCESS;
        int previousReads = reads.get(); lookupWorker().execute(); retryWorker().execute();
        service(gateway()).executeFirst(current.getAttemptId()); service(gateway()).execute(current.getAttemptId());
        assertThat(reads.get()).isGreaterThan(previousReads); assertThat(bodies).hasSize(1);
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(hold.getHoldId());
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(stored().getLookupSchedule().getNextAt()).isNotNull();
        assertThat(payments.findByPaymentId(current.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test void explicitResumeAfterInvalidRequestKeepsBodyAndCannotReleaseAReplacementHold() {
        received(); mode = Mode.INVALID_REQUEST; service(gateway()).executeFirst(current.getAttemptId());
        String frozen = stored().getCancellationRequestBody(); RefundCancellationHold first = currentHold();
        RefundCancellationResumeRequest original = resumeCommand(first);
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); RefundCancellationResumeReceipt receipt = resume.execute(original);
        retryWorker().execute(); RefundCancellationHold second = currentHold();
        assertThat(second.getHoldId()).isNotEqualTo(first.getHoldId()); assertThat(second.getReason()).isEqualTo("PG_REQUEST_INVALID");
        assertThat(bodies).containsExactly(frozen, frozen); assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        when(clock.instant()).thenReturn(BASE.plusSeconds(20)); assertThat(resume.execute(original)).isEqualTo(receipt);
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(second.getHoldId());
        assertThatThrownBy(() -> resume.execute(resumeCommand(first))).isInstanceOf(BusinessException.class);
        resume.execute(resumeCommand(second)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).containsExactly(frozen, frozen, frozen);
        assertThat(keys.stream().distinct().toList()).containsExactly("\"" + current.getAttemptId() + "\"");
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(holds.findByHoldId(first.getHoldId()).orElseThrow().getResumeRequestId()).isEqualTo(original.requestId());
        assertThat(resume.execute(original)).isEqualTo(receipt);
    }

    @Test void resultLookupOutageAfterInvalidRequestCannotLoseTheHold() {
        received(); mode = Mode.INVALID_LOOKUP_DOWN;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        String holdId = currentHold().getHoldId();
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); mode = Mode.RETRY_SUCCESS;
        lookupWorker().execute(); retryWorker().execute(); service(gateway()).executeFirst(current.getAttemptId());
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(holdId); assertThat(holds.count()).isEqualTo(1);
        assertThat(bodies).hasSize(1); assertThat(stored().getFailureEventId()).isNull(); assertThat(resultEventCount()).isZero();
    }

    @Test void invalidRequestResumeCannotExtendTheOriginalWindow() {
        received(); mode = Mode.INVALID_REQUEST; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold(); when(clock.instant()).thenReturn(BASE.plusSeconds(10800));
        assertThatThrownBy(() -> resume.execute(resumeCommand(hold))).isInstanceOf(BusinessException.class);
        int previousReads = reads.get(); lookupWorker().execute(); retryWorker().execute();
        assertThat(reads.get()).isGreaterThan(previousReads); assertThat(bodies).hasSize(1);
        assertThat(stored().getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(stored().getActiveCancellationHoldId()).isEqualTo(hold.getHoldId());
        assertThat(holds.findByHoldId(hold.getHoldId()).orElseThrow().getResumeRequestId()).isNull();
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull(); assertThat(resultEventCount()).isZero();
    }

    @Test void lookupCanCompleteAnInvalidRequestHeldRefundAndBlocksResume() {
        received(); mode = Mode.INVALID_REQUEST; service(gateway()).executeFirst(current.getAttemptId());
        RefundCancellationHold hold = currentHold(); cancelled = current.getAmount(); when(clock.instant()).thenReturn(BASE.plusSeconds(5));
        lookupWorker().execute();
        assertThat(stored().getRefundConfirmedAt()).isEqualTo(BASE.plusSeconds(5));
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThatThrownBy(() -> resume.execute(resumeCommand(hold))).isInstanceOf(BusinessException.class);
        retryWorker().execute(); assertThat(bodies).hasSize(1); assertThat(resultEventCount()).isEqualTo(1);
        assertThat(stored().getFailureEventId()).isNull();
    }

    @Test void resumedInvalidRequestStopsOnPartialBalance() {
        received(); mode = Mode.INVALID_REQUEST; service(gateway()).executeFirst(current.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); resume.execute(resumeCommand(currentHold()));
        cancelled = 3000; mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).hasSize(1); assertThat(stored().getCancellationBalanceChangedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(stored().getCancellationSchedule().getNextAt()).isNull(); assertThat(stored().getFailureEventId()).isNull();
        assertThat(stored().getLookupSchedule().getNextAt()).isNotNull(); assertThat(resultEventCount()).isZero();
    }

    @Test void invalidRequestHoldInsertAndPauseRollBackTogether() {
        received(); mode = Mode.INVALID_REQUEST;
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            RefundCancellationHold inserted = call.getArgument(0); entityManager.persist(inserted); entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT reason FROM refund_cancellation_hold", String.class)).isEqualTo("PG_REQUEST_INVALID");
            throw new IllegalStateException("after invalid hold insert flush");
        }).when(holds).saveAndFlush(any(RefundCancellationHold.class)));
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId()))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining("after invalid hold insert flush");
        assertThat(holds.count()).isZero(); assertThat(stored().getActiveCancellationHoldId()).isNull();
        reset(holds); when(clock.instant()).thenReturn(BASE.plusSeconds(10)); retryWorker().execute();
        assertThat(currentHold().getReason()).isEqualTo("PG_REQUEST_INVALID");
        assertThat(bodies).hasSize(2); assertThat(resultEventCount()).isZero();
    }

    @Test void lateInvalidRequestFromExpiredOwnerCannotPauseSuccessfulRefund() throws Exception {
        received(); mode = Mode.DELAYED_INVALID; ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> old = pool.submit(() -> service(gateway()).executeFirst(current.getAttemptId()));
            assertThat(responseReady.await(15, TimeUnit.SECONDS)).isTrue();
            when(clock.instant()).thenReturn(BASE.plusSeconds(250)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
            assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
            releaseResponse.countDown(); old.get(15, TimeUnit.SECONDS);
            assertThat(holds.count()).isEqualTo(1); RefundCancellationHold late = holds.findAll().getFirst();
            assertThat(late.getReason()).isEqualTo("PG_REQUEST_INVALID"); assertThat(late.isActivated()).isFalse();
            assertThat(stored().getActiveCancellationHoldId()).isNull(); assertThat(bodies).hasSize(2);
            assertThat(resultEventCount()).isEqualTo(1);
        } finally { releaseResponse.countDown(); pool.shutdownNow(); }
    }

    @Test void unrecognized400StaysUnconfirmedWithoutOperatorHold() {
        received(); mode = Mode.UNRECOGNIZED_BAD_REQUEST;
        assertThatThrownBy(() -> service(gateway()).executeFirst(current.getAttemptId())).isExactlyInstanceOf(PgUnavailable.class);
        assertThat(holds.count()).isZero(); assertThat(stored().getActiveCancellationHoldId()).isNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(resultEventCount()).isZero();
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); mode = Mode.RETRY_SUCCESS; retryWorker().execute();
        assertThat(bodies).hasSize(2); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
    }

    @Test void v25MigrationKeepsExistingHoldsAndResumeHistoryAndRejectsUnknownReasons() {
        received(); mode = Mode.FORBIDDEN; service(gateway()).executeFirst(current.getAttemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(10)); resume.execute(resumeCommand(currentHold())); retryWorker().execute();
        String active = currentHold().getHoldId();
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        String schema = "refund_hold_upgrade_" + id().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("24").load().migrate();
            JdbcTemplate migration = new JdbcTemplate(source);
            migration.update("INSERT INTO purchase_payment SELECT * FROM " + mysql.getDatabaseName() + ".purchase_payment WHERE payment_id=?", current.getPaymentId());
            List<String> columns = migration.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=?"
                    + " AND TABLE_NAME='refund_execution' AND GENERATION_EXPRESSION='' ORDER BY ORDINAL_POSITION", String.class, schema);
            assertThat(columns).doesNotContain("pending_payment_guard");
            String projection = String.join(",", columns);
            migration.update("INSERT INTO refund_execution(" + projection + ") SELECT " + projection + " FROM "
                    + mysql.getDatabaseName() + ".refund_execution WHERE attempt_id=?", current.getAttemptId());
            migration.update("INSERT INTO refund_cancellation_hold SELECT * FROM " + mysql.getDatabaseName() + ".refund_cancellation_hold WHERE attempt_id=?", current.getAttemptId());
            List<Map<String, Object>> original = migration.queryForList("SELECT * FROM refund_cancellation_hold ORDER BY id");
            assertThat(original).hasSize(2);
            Map<String, Object> execution = migration.queryForMap("SELECT * FROM refund_execution");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(migration.queryForList("SELECT * FROM refund_cancellation_hold ORDER BY id")).isEqualTo(original);
            assertThat(migration.queryForMap("SELECT * FROM refund_execution")).containsAllEntriesOf(execution);
            assertThat(migration.queryForObject("SELECT active_cancel_hold_id FROM refund_execution", String.class)).isEqualTo(active);
            assertThatThrownBy(() -> migration.update("UPDATE refund_cancellation_hold SET reason='UNKNOWN_REASON' WHERE hold_id=?", active))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_cancel_hold_observation");
            assertThat(migration.queryForList("SELECT * FROM refund_cancellation_hold ORDER BY id")).isEqualTo(original);
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    private ResolveRefundLookup lookupWorker() {
        return new ResolveRefundLookup(payments, executions, reconcile(), transactions, clock, Runnable::run, Duration.ofMinutes(2), 20);
    }

    private RefundCancellationHold currentHold() {
        return holds.findByHoldId(stored().getActiveCancellationHoldId()).orElseThrow();
    }

    private RefundCancellationResumeRequest resumeCommand(RefundCancellationHold hold) {
        return new RefundCancellationResumeRequest(id(), current.getAttemptId(), hold.getHoldId(), "operator-fixture", "provider rejection reviewed");
    }

    private ResolveRefundRetry retryWorker() { return new ResolveRefundRetry(executions, service(gateway()), clock, Runnable::run, 20); }
    private ResolveRefundRetry authenticatedWorker(String apiSecret) {
        return new ResolveRefundRetry(executions, authenticatedService(apiSecret), clock, Runnable::run, 20);
    }

    private RequestRefundCancellation authenticatedService(String apiSecret) {
        RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + pg.getAddress().getPort())
                .defaultHeader("Authorization", "PortOne " + apiSecret).build();
        return service(new PortOneRefundCancellationGateway(client, STORE, CHANNEL, clock), client);
    }

    private void rememberFailure(RefundExecution execution) {
        Payment payment = payments.findByPaymentId(execution.getPaymentId()).orElseThrow(); retryHistory.add(execution.cancellationReceipt(payment));
    }
    private RefundExecution receiveNext(RefundExecution previous) {
        when(clock.instant()).thenReturn(previous.getReceivedAt().plusSeconds(86400)); return receive.execute(nextRequest(previous));
    }
    private RefundExecutionRequest nextRequest(RefundExecution previous) {
        return new RefundExecutionRequest(id(), clock.instant(), previous.getRefundId(), id(), previous.getOrderNo(), previous.getPaymentId(), previous.getBuyerSubject(),
                previous.getAmount(), previous.getCurrency(), id(), previous.getAttemptId());
    }
    private String retryRows() throws Exception {
        List<String> rows = new ArrayList<>();
        for (PgRefundCancellationReceipt known : retryHistory) rows.add(new ObjectMapper().writeValueAsString(Map.of("status", "FAILED", "id", known.cancellationId(),
                "totalAmount", known.purchase().amount(), "taxFreeAmount", known.taxFreeAmount(), "vatAmount", known.vatAmount(), "reason", known.reason(), "requestedAt", known.requestedAt().toString())));
        if (mode == Mode.RETRY_OTHER_PENDING) rows.add(failureRow("REQUESTED").replace("cancel-1", "someone-else"));
        return "[" + String.join(",", rows) + "]";
    }

    private String failureRow(String state) {
        return "{\"status\":\""+state+"\",\"id\":\"cancel-1\",\"totalAmount\":"+current.getAmount()
                +",\"taxFreeAmount\":0,\"vatAmount\":0,\"reason\":\"ESD refund "+current.getRefundId()+"\",\"requestedAt\":\""+BASE+"\"}";
    }

    private String paymentBody() throws Exception {
        String status = cancelled == 0 ? "PAID" : cancelled == current.getAmount() ? "CANCELLED" : "PARTIAL_CANCELLED";
        String rows = cancelled == 0 ? "[]" : "[{\"status\":\"SUCCEEDED\",\"id\":\"cancel-1\",\"totalAmount\":" + cancelled + ",\"cancelledAt\":\"" + BASE + "\"}]";
        if (!bodies.isEmpty() && (mode.name().startsWith("FAILED_") || mode == Mode.REQUESTED_THEN_FAILED)) {
            rows = "[" + failureRow(mode == Mode.REQUESTED_THEN_FAILED ? "REQUESTED" : "FAILED")
                    + (mode == Mode.FAILED_MANUAL_FULL ? ",{\"status\":\"SUCCEEDED\",\"id\":\"cancel-manual\",\"totalAmount\":"
                        + current.getAmount() + ",\"cancelledAt\":\"" + BASE + "\"}" : "")
                    + (mode == Mode.FAILED_OTHER_PENDING ? "," + failureRow("REQUESTED").replace("cancel-1", "someone-else") : "") + "]";
        }
        if (mode.name().startsWith("RETRY_")) rows = retryRows();
        return """
                {"status":"%s","id":"%s","storeId":"%s","version":"V2","currency":"KRW",
                "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                "amount":{"total":%d,"paid":%d,"discount":0,"cancelled":%d},"cancellations":%s,
                "customData":"{\\"orderNo\\":\\"%s\\"}","paidAt":"%s","pgTxId":"pg-original","cancelledAt":"%s"}
                """.formatted(status, current.getPaymentId(), STORE, CHANNEL, current.getAmount(), current.getAmount(), cancelled,
                        rows, current.getOrderNo(), BASE.minusSeconds(60), BASE);
    }
    private RestClient client() { return RestClient.builder().baseUrl("http://127.0.0.1:" + pg.getAddress().getPort()).build(); }
    private PortOneRefundCancellationGateway gateway() { return new PortOneRefundCancellationGateway(client(), STORE, CHANNEL, clock); }
    private RequestRefundCancellation service(RefundCancellationGateway gateway) {
        return service(gateway, client());
    }
    private RequestRefundCancellation service(RefundCancellationGateway gateway, RestClient client) {
        ReconcileRefundPayment reconcile = new ReconcileRefundPayment(payments, executions,
                new PortOneRefundPaymentLookup(client, STORE, CHANNEL), outbox, transactions, clock);
        return new RequestRefundCancellation(payments, executions, gateway, reconcile, transactions, clock,
                new RecordRefundCancellationSuccess(payments, executions, outbox, transactions, clock), Duration.ofMinutes(4), holds, new RecordRefundPreparation(outbox));
    }
    private RefundExecution stored() { return executions.findByAttemptId(current.getAttemptId()).orElseThrow(); }
    private int resultEventCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'",Integer.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private enum Mode { COMPLETE, RACE, UNAVAILABLE, COMPLETE_THEN_UNAVAILABLE, DIRECT_SUCCESS, DELAYED_SUCCESS, FAILED_ZERO, FAILED_MANUAL_FULL, FAILED_LOOKUP_DOWN, FAILED_OTHER_PENDING, FAILED_PARTIAL, REQUESTED_THEN_FAILED, RETRY_SUCCESS, RETRY_FAILURE, RETRY_OTHER_PENDING, FORBIDDEN, DELAYED_FORBIDDEN, FORBIDDEN_LOOKUP_DOWN, POST_NOT_FOUND, INVALID_REQUEST, INVALID_LOOKUP_DOWN, DELAYED_INVALID, UNRECOGNIZED_BAD_REQUEST }
    private enum AuthenticationGate { NONE, ALL, POST_ONLY, GET_AFTER_POST }
    private enum LookupState { AVAILABLE, NOT_FOUND, NOT_FOUND_AFTER_POST, WRONG_PURCHASE, FORBIDDEN, FORBIDDEN_AFTER_POST }
}
