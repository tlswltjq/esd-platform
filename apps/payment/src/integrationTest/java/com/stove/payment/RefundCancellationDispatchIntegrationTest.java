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
import com.stove.payment.core.domain.PgRefundCancellationSuccess;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.RefundCancellation;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import com.stove.payment.adapter.pg.PortOneRefundCancellationGateway;
import com.stove.payment.adapter.pg.PortOneRefundPaymentLookup;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @MockitoBean Clock clock;
    @MockitoSpyBean RefundExecutionRepository executions;
    @MockitoSpyBean OutboxRecorder outbox;
    private HttpServer pg;
    private RefundExecution current;
    private volatile Mode mode;
    private volatile long cancelled;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final Set<String> completedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    private CountDownLatch responseReady = new CountDownLatch(1);
    private CountDownLatch releaseResponse = new CountDownLatch(1);
    private ExecutorService pgThreads;

    @BeforeEach void initialize() throws Exception {
        clear(); when(clock.instant()).thenReturn(BASE);
        mode = Mode.COMPLETE; responseReady = new CountDownLatch(1); releaseResponse = new CountDownLatch(1);
        pg = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pgThreads = Executors.newFixedThreadPool(4); pg.setExecutor(pgThreads);
        pg.createContext("/payments/", exchange -> {
            try {
                if ("GET".equals(exchange.getRequestMethod())) {
                    if ((mode == Mode.DIRECT_SUCCESS || mode == Mode.FAILED_LOOKUP_DOWN) && !bodies.isEmpty()) {
                        exchange.sendResponseHeaders(503, -1); exchange.close(); return;
                    }
                    byte[] bytes = paymentBody().getBytes(StandardCharsets.UTF_8);
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
                if (mode == Mode.RACE) { cancelled = 3000; status = 409; reply = "{\"type\":\"CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN\"}"; }
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
        reset(executions, outbox); bodies.clear(); keys.clear(); completedKeys.clear(); callbackFailure.set(null); cancelled = 0;
        jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM refund_execution");
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
        assertThat(count("outbox_event")).isEqualTo(1);
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
        assertThat(stored().getRefundConfirmedAt()).isNull(); assertThat(count("outbox_event")).isZero();
        service(gateway()).execute(current.getAttemptId()); assertThat(bodies).hasSize(1);
    }

    @Test void anUnknownPostOutcomeCanStillCompleteTheCustomerRefundByQuerying() {
        received(); mode = Mode.COMPLETE_THEN_UNAVAILABLE;
        service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(stored().getRefundConfirmedAt()).isNotNull();
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(count("outbox_event")).isEqualTo(1);
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
            assertThat(completedKeys).hasSize(1); assertThat(count("outbox_event")).isEqualTo(1);
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
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_event", String.class)).contains("PG_RESPONSE");
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
            call.callRealMethod(); throw new IllegalStateException("after success outbox insert");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getRefundConfirmedAt()).isNull(); assertThat(stored().getCancellationSucceededAt()).isNull();
        assertThat(count("outbox_event")).isZero(); assertThat(count("refund_cancellation_evidence")).isZero();
        reset(outbox); successRecorder().execute(current.getAttemptId(), successProof());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED); assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void concurrentReceiptRecordersKeepOneSuccessAndOneCompletionEvent() throws Exception {
        received();
        new RecordRefundCancellationPreparation(executions, transactions, clock).execute(current.getAttemptId());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> executions.lockByAttemptId(current.getAttemptId()).orElseThrow()
                .freezeCancellationBody(gateway().prepareBody(current.request())));
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            PgRefundCancellationSuccess proof = successProof();
            List<Future<?>> work = List.of(pool.submit(() -> { start.await(); successRecorder().execute(current.getAttemptId(), proof); return null; }),
                    pool.submit(() -> { start.await(); successRecorder().execute(current.getAttemptId(), proof); return null; }));
            start.countDown(); for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
            assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED); assertThat(count("outbox_event")).isEqualTo(1);
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
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event", String.class)).isEqualTo("RefundExecutionFailed");
        service(gateway()).execute(current.getAttemptId()); reconcile().execute(current.getAttemptId());
        assertThat(bodies).hasSize(1); assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void aFailedOwnRequestDoesNotRestoreAccessWhenAnotherCancellationAlreadyRefundedTheWholePurchase() {
        received(); mode = Mode.FAILED_MANUAL_FULL; service(gateway()).execute(current.getAttemptId());
        assertThat(callbackFailure.get()).isNull(); assertThat(bodies).hasSize(1);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored().getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(stored().getConfirmedCancellations().getFirst().getCancellationId()).isEqualTo("cancel-manual");
        assertThat(stored().getFailureEventId()).isNull();
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event", String.class)).isEqualTo("RefundPaymentConfirmed");
    }

    @Test void aRequestedReceiptSurvivesRestartAndItsOwnFailedIdCanLaterBeVerified() {
        received(); mode = Mode.REQUESTED_THEN_FAILED; service(gateway()).execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(stored().getReceiptCancellationId()).isEqualTo("cancel-1");
        assertThat(count("outbox_event")).isZero();
        mode = Mode.FAILED_ZERO; when(clock.instant()).thenReturn(BASE.plusSeconds(5));
        reconcile().execute(current.getAttemptId()); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.FAILED);
        assertThat(count("outbox_event")).isEqualTo(1); assertThat(bodies).hasSize(1);
    }

    @Test void aFailedReplyWithAnUnavailableQueryOrOtherPendingCancellationCannotAuthorizeRestoration() {
        received(); mode = Mode.FAILED_LOOKUP_DOWN;
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(PgUnavailable.class);
        assertThat(stored().getReceiptState().name()).isEqualTo("FAILED"); assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        mode = Mode.FAILED_OTHER_PENDING; reconcile().execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(count("outbox_event")).isZero();
        mode = Mode.FAILED_PARTIAL; cancelled = 3000; reconcile().execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(count("outbox_event")).isZero();
    }

    @Test void verifiedFailureAndOutboxRollbackTogetherThenResumeFromThePersistedReceipt() {
        received(); mode = Mode.FAILED_ZERO;
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); executions.flush(); throw new IllegalStateException("after failure flush and outbox insert");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        assertThatThrownBy(() -> service(gateway()).execute(current.getAttemptId())).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(stored().getFailureEventId()).isNull();
        assertThat(stored().getReceiptState().name()).isEqualTo("FAILED"); assertThat(count("outbox_event")).isZero();
        reset(outbox); reconcile().execute(current.getAttemptId());
        assertThat(stored().getStatus()).isEqualTo(RefundExecutionStatus.FAILED); assertThat(count("outbox_event")).isEqualTo(1); assertThat(bodies).hasSize(1);
    }

    private String failureRow(String state) {
        return "{\"status\":\""+state+"\",\"id\":\"cancel-1\",\"totalAmount\":"+current.getAmount()
                +",\"taxFreeAmount\":0,\"vatAmount\":0,\"reason\":\"ESD refund "+current.getRefundId()+"\",\"requestedAt\":\""+BASE+"\"}";
    }

    private String paymentBody() {
        String status = cancelled == 0 ? "PAID" : cancelled == current.getAmount() ? "CANCELLED" : "PARTIAL_CANCELLED";
        String rows = cancelled == 0 ? "[]" : "[{\"status\":\"SUCCEEDED\",\"id\":\"cancel-1\",\"totalAmount\":" + cancelled + ",\"cancelledAt\":\"" + BASE + "\"}]";
        if (!bodies.isEmpty() && (mode.name().startsWith("FAILED_") || mode == Mode.REQUESTED_THEN_FAILED)) {
            rows = "[" + failureRow(mode == Mode.REQUESTED_THEN_FAILED ? "REQUESTED" : "FAILED")
                    + (mode == Mode.FAILED_MANUAL_FULL ? ",{\"status\":\"SUCCEEDED\",\"id\":\"cancel-manual\",\"totalAmount\":"
                        + current.getAmount() + ",\"cancelledAt\":\"" + BASE + "\"}" : "")
                    + (mode == Mode.FAILED_OTHER_PENDING ? "," + failureRow("REQUESTED").replace("cancel-1", "someone-else") : "") + "]";
        }
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
        ReconcileRefundPayment reconcile = new ReconcileRefundPayment(payments, executions,
                new PortOneRefundPaymentLookup(client(), STORE, CHANNEL), outbox, transactions, clock);
        return new RequestRefundCancellation(payments, executions, gateway, reconcile, transactions, clock,
                new RecordRefundCancellationSuccess(payments, executions, outbox, transactions, clock));
    }
    private RefundExecution stored() { return executions.findByAttemptId(current.getAttemptId()).orElseThrow(); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private enum Mode { COMPLETE, RACE, UNAVAILABLE, COMPLETE_THEN_UNAVAILABLE, DIRECT_SUCCESS, DELAYED_SUCCESS, FAILED_ZERO, FAILED_MANUAL_FULL, FAILED_LOOKUP_DOWN, FAILED_OTHER_PENDING, FAILED_PARTIAL, REQUESTED_THEN_FAILED }
}
