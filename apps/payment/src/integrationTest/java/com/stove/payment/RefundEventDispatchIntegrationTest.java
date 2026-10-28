package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.SECRET;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.RefundFixtures.event;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.port.RefundCancellationGateway;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RequestRefundCancellation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = {"stove.payment.pg.provider=PORTONE_TEST",
        "stove.payment.refund-execution.enabled=true", "stove.payment.refund-lookup.enabled=true", "stove.payment.refund-retry.enabled=true"})
class RefundEventDispatchIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant BASE = Payment.now();
    private static final HttpServer PG;
    static {
        try {
            PG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PG.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> PG.stop(0)));
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void provider(DynamicPropertyRegistry registry) {
        registry.add("stove.payment.portone.base-url", () -> "http://127.0.0.1:" + PG.getAddress().getPort());
        registry.add("stove.payment.portone.api-secret", () -> "fixture-only-api-secret");
        registry.add("stove.payment.portone.store-id", () -> STORE);
        registry.add("stove.payment.portone.channel-key", () -> CHANNEL);
        registry.add("stove.payment.portone.webhook-secret", () -> SECRET);
        registry.add("stove.payment.portone.webhook-url", () -> "https://fixture.example/webhook");
    }
    @Autowired PaymentRepository payments;
    @Autowired RefundExecutionRepository executions;
    @Autowired ReceiveRefundExecution receive;
    @Autowired RequestRefundCancellation cancellations;
    @Autowired RefundCancellationGateway gateway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired @Qualifier("refundRetryExecutor") ThreadPoolTaskExecutor retryExecutor;
    @Autowired @Qualifier("refundLookupExecutor") ThreadPoolTaskExecutor lookupExecutor;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;
    private final Map<String, RefundExecutionRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, Payment> purchases = new ConcurrentHashMap<>();
    private final List<String> posts = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> httpFailure = new AtomicReference<>();
    private final AtomicBoolean succeeded = new AtomicBoolean();
    private CountDownLatch postStarted = new CountDownLatch(1);
    private CountDownLatch releasePost = new CountDownLatch(1);
    private volatile Mode mode;

    @BeforeEach void initialize() {
        clearDatabase(); when(clock.instant()).thenReturn(BASE);
        mode = Mode.SUCCESS;
        PG.createContext("/payments/", this::respond);
    }
    @AfterEach void finish() {
        releasePost.countDown(); listeners.stop();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> retryExecutor.getActiveCount() == 0 && lookupExecutor.getActiveCount() == 0);
        PG.removeContext("/payments/");
        assertThat(httpFailure.get()).isNull();
        clearDatabase();
    }
    private void clearDatabase() {
        listeners.stop(); reset(outbox);
        jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void kafkaStartsTheFirstPostAfterCommitAndDuplicatesPreserveOneFinancialResult() throws Exception {
        RefundExecutionRequest input = paidRequest();
        listeners.start(); send(input); awaitConsumed(send(input));
        RefundExecution stored = stored(input);
        assertThat(posts).containsExactly(input.attemptId());
        assertThat(stored.getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(stored.getRefundConfirmationSource()).isEqualTo("PG_RESPONSE");
        assertThat(count(EventType.REFUND_PAYMENT_CONFIRMED)).isEqualTo(1);
        // A direct result carries both facts in one confirmation; a separate success is only for a late result.
        assertThat(count(EventType.REFUND_EXECUTION_SUCCEEDED)).isZero();
        assertThat(payments.findByPaymentId(input.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(listeners.getListenerContainers()).anySatisfy(container -> {
            assertThat(container.getGroupId()).isEqualTo(ReceiveRefundExecution.CONSUMER_GROUP);
            assertThat(container.getContainerProperties().getKafkaConsumerProperties())
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        });
    }

    @Test void aReceiptCommittedBeforeProcessInterruptionStillSendsOnKafkaRedelivery() throws Exception {
        RefundExecutionRequest input = paidRequest(); receive.execute(input);
        Instant receivedAt = stored(input).getReceivedAt();
        assertThat(stored(input).getFirstCancellationPreparedAt()).isNull();
        listeners.start(); awaitConsumed(send(input));
        assertThat(posts).containsExactly(input.attemptId());
        assertThat(stored(input).getReceivedAt()).isEqualTo(receivedAt);
        assertThat(stored(input).getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class,
                ReceiveRefundExecution.CONSUMER_GROUP)).isEqualTo(1);
    }

    @Test void lostResponseCommitsKafkaWithoutRepeatedPostAndTheExistingGetWorkerFinishes() throws Exception {
        mode = Mode.UNKNOWN;
        RefundExecutionRequest input = paidRequest(); listeners.start(); awaitConsumed(send(input));
        awaitConsumed(send(input));
        assertThat(posts).containsExactly(input.attemptId());
        RefundExecution pending = stored(input);
        assertThat(pending.getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(pending.getRefundConfirmedAt()).isNull();
        assertThat(pending.getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(5));
        assertThat(count(EventType.REFUND_EXECUTION_FAILED)).isZero();
        succeeded.set(true); mode = Mode.SUCCESS; when(clock.instant()).thenReturn(BASE.plusSeconds(5));
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(stored(input).getRefundConfirmedAt()).isNotNull();
            assertThat(stored(input).getLookupSchedule().getNextAt()).isNull();
        });
        assertThat(posts).hasSize(1);
        assertThat(stored(input).getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
    }

    @Test void preparedButUnobservedDispatchDoesNotBecomeAnImplicitKafkaPostRetry() throws Exception {
        RefundExecutionRequest input = paidRequest(); receive.execute(input);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            RefundExecution current = executions.lockByAttemptId(input.attemptId()).orElseThrow();
            current.prepareCancellation(BASE); current.freezeCancellationBody(gateway.prepareBody(input));
        });
        listeners.start(); awaitConsumed(send(input));
        assertThat(posts).isEmpty();
        assertThat(stored(input).getStatus()).isEqualTo(RefundExecutionStatus.PENDING);
        assertThat(stored(input).getLookupSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(5));
    }

    @Test void aDatabaseFailureAfterPgSuccessRetriesTheEventWithGetAndOnePost() throws Exception {
        RefundExecutionRequest input = paidRequest();
        AtomicBoolean first = new AtomicBoolean(true);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            Object result = call.callRealMethod();
            if (first.getAndSet(false)) {
                executions.flush();
                throw new TransientDataAccessResourceException("abort supplier result transaction");
            }
            return result;
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        listeners.start(); awaitConsumed(send(input));
        assertThat(posts).containsExactly(input.attemptId());
        assertThat(stored(input).getRefundConfirmedAt()).isNotNull();
        assertThat(stored(input).getRefundConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
        assertThat(count(EventType.REFUND_PAYMENT_CONFIRMED)).isEqualTo(1);
        assertThat(count(EventType.REFUND_EXECUTION_SUCCEEDED)).isZero();
    }

    @Test void slowPgKeepsTheCurrentRecordUncommittedAndTheNextPartitionRecordWaiting() throws Exception {
        mode = Mode.BLOCK;
        RefundExecutionRequest first = paidRequest(); RefundExecutionRequest second = paidRequest();
        listeners.start(); RecordMetadata sent = send(first);
        assertThat(postStarted.await(20, TimeUnit.SECONDS)).isTrue();
        RecordMetadata next = send(second);
        Awaitility.await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(executions.findByAttemptId(second.attemptId())).isEmpty();
            assertThat(committedOffset(sent)).isLessThanOrEqualTo(sent.offset());
        });
        releasePost.countDown(); awaitConsumed(next);
        assertThat(posts).containsExactly(first.attemptId(), second.attemptId());
    }

    @Test void simultaneousFirstDispatchCallsHaveOnlyOneHttpOwner() throws Exception {
        mode = Mode.BLOCK;
        RefundExecutionRequest input = paidRequest(); receive.execute(input);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> cancellations.executeFirst(input.attemptId()));
            assertThat(postStarted.await(20, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> cancellations.executeFirst(input.attemptId()));
            releasePost.countDown();
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertThat(posts).containsExactly(input.attemptId());
            assertThat(stored(input).getRefundConfirmedAt()).isNotNull();
        } finally {
            releasePost.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void kafkaUncertaintyResumesAutomaticallyWithTenThirtySixtySecondWaitsAndTheSameAttempt() throws Exception {
        mode = Mode.UNKNOWN;
        RefundExecutionRequest input = paidRequest(); listeners.start(); awaitConsumed(send(input));
        assertThat(stored(input).getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(10));
        when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stored(input).getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(40)));
        assertThat(posts).containsExactly(input.attemptId());
        when(clock.instant()).thenReturn(BASE.plusSeconds(40));
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stored(input).getCancellationSchedule().getNextAt()).isEqualTo(BASE.plusSeconds(100)));
        mode = Mode.SUCCESS; when(clock.instant()).thenReturn(BASE.plusSeconds(100));
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(stored(input).getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
            assertThat(stored(input).getCancellationSchedule().getNextAt()).isNull();
        });
        assertThat(posts).containsExactly(input.attemptId(), input.attemptId());
        assertThat(stored(input).getFirstCancellationPreparedAt()).isEqualTo(BASE);
        assertThat(count(EventType.REFUND_PAYMENT_CONFIRMED)).isEqualTo(1);
    }

    @Test void theRetryTimerDoesNotOverlapAnInitialKafkaHttpCallThatIsStillRunning() throws Exception {
        mode = Mode.BLOCK;
        RefundExecutionRequest input = paidRequest(); listeners.start(); RecordMetadata sent = send(input);
        assertThat(postStarted.await(20, TimeUnit.SECONDS)).isTrue();
        when(clock.instant()).thenReturn(BASE.plusSeconds(60));
        Awaitility.await().during(Duration.ofMillis(1200)).atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            assertThat(posts).containsExactly(input.attemptId());
            assertThat(stored(input).getCancellationSchedule().getClaimId()).isNotNull();
            assertThat(committedOffset(sent)).isLessThanOrEqualTo(sent.offset());
        });
        releasePost.countDown(); awaitConsumed(sent);
        assertThat(stored(input).getStatus()).isEqualTo(RefundExecutionStatus.SUCCEEDED);
        assertThat(posts).hasSize(1);
    }

    private RefundExecutionRequest paidRequest() {
        Payment paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW",
                "pg-" + paid.getPaymentId(), BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        payments.saveAndFlush(paid);
        RefundExecutionRequest input = execution(paid);
        requests.put(input.paymentId(), input); purchases.put(input.paymentId(), paid);
        return input;
    }
    private RefundExecution stored(RefundExecutionRequest input) { return executions.findByAttemptId(input.attemptId()).orElseThrow(); }
    private int count(String eventType) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=?", Integer.class, eventType); }

    private RecordMetadata send(RefundExecutionRequest input) throws Exception {
        DomainEvent event = event(input);
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), 0, event.partitionKey(), mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }
    private long committedOffset(RecordMetadata sent) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers",
                kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers")))) {
            Map<TopicPartition, OffsetAndMetadata> offsets;
            try { offsets = admin.listConsumerGroupOffsets(ReceiveRefundExecution.CONSUMER_GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof GroupIdNotFoundException) return -1;
                throw failure;
            }
            OffsetAndMetadata offset = offsets.get(new TopicPartition(sent.topic(), sent.partition()));
            return offset == null ? -1 : offset.offset();
        }
    }
    private void awaitConsumed(RecordMetadata sent) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(committedOffset(sent)).isGreaterThan(sent.offset()));
        assertThat(httpFailure.get()).isNull();
    }

    private void respond(HttpExchange exchange) {
        try {
            String paymentId = exchange.getRequestURI().getPath().split("/")[2];
            RefundExecutionRequest input = requests.get(paymentId); Payment paid = purchases.get(paymentId);
            assertThat(input).isNotNull();
            if ("GET".equals(exchange.getRequestMethod())) {
                if (mode == Mode.UNKNOWN && !posts.isEmpty()) { reply(exchange, 503, "{}"); return; }
                reply(exchange, 200, paymentBody(input, paid)); return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RefundExecution committed = stored(input);
            assertThat(committed.getFirstCancellationPreparedAt()).isNotNull();
            assertThat(committed.getCancellationRequestBody()).isEqualTo(body);
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("\"" + input.attemptId() + "\"");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=? AND consumer_group=?", Integer.class,
                    input.eventId(), ReceiveRefundExecution.CONSUMER_GROUP)).isEqualTo(1);
            posts.add(input.attemptId()); postStarted.countDown();
            if (mode == Mode.BLOCK && !releasePost.await(20, TimeUnit.SECONDS)) throw new AssertionError("PG fixture was not released");
            if (mode == Mode.UNKNOWN) { reply(exchange, 503, "{}"); return; }
            JsonNode json = mapper.readTree(body);
            assertThat(json.path("amount").longValue()).isEqualTo(input.amount());
            succeeded.set(true);
            reply(exchange, 200, mapper.writeValueAsString(Map.of("cancellation", cancellationRow(input, "SUCCEEDED"))));
        } catch (Throwable failure) { httpFailure.compareAndSet(null, failure); exchange.close(); }
    }
    private String paymentBody(RefundExecutionRequest input, Payment paid) throws Exception {
        // Completion is per purchase: another completed refund cannot change this purchase's preflight.
        boolean cancelled = succeeded.get() && (posts.contains(input.attemptId()) || mode == Mode.UNKNOWN);
        HashMap<String, Object> body = new HashMap<>();
        body.put("id", input.paymentId()); body.put("storeId", STORE); body.put("version", "V2"); body.put("currency", "KRW");
        body.put("status", cancelled ? "CANCELLED" : "PAID");
        body.put("channel", Map.of("type", "TEST", "key", CHANNEL, "pgProvider", "TOSSPAY_V2"));
        body.put("amount", Map.of("total", input.amount(), "paid", input.amount(), "discount", 0, "cancelled", cancelled ? input.amount() : 0));
        body.put("pgTxId", paid.getProviderTransactionId()); body.put("paidAt", paid.getProviderOccurredAt().toString());
        body.put("customData", mapper.writeValueAsString(Map.of("orderNo", input.orderNo())));
        body.put("cancellations", cancelled ? List.of(cancellationRow(input, "SUCCEEDED")) : List.of());
        if (cancelled) body.put("cancelledAt", BASE.toString());
        return mapper.writeValueAsString(body);
    }
    private Map<String, Object> cancellationRow(RefundExecutionRequest input, String status) {
        return Map.of("id", "cancel-" + input.attemptId(), "status", status, "totalAmount", input.amount(),
                "taxFreeAmount", 0, "vatAmount", 0, "reason", "ESD refund " + input.refundId(), "requestedAt", BASE.toString(), "cancelledAt", BASE.toString());
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private enum Mode { SUCCESS, UNKNOWN, BLOCK }
}
