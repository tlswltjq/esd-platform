package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.SECRET;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.RefundFixtures.event;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * D-050: the durable lookup and retry workers continue a refund under the parent saved at reception
 * (refund_execution_trace, decision 28). A refund without a saved parent starts a new root as before.
 */
@AutoConfigureObservability
@Import(RefundWorkerTracingIntegrationTest.TracingConfiguration.class)
@TestPropertySource(properties = {"stove.payment.pg.provider=PORTONE_TEST", "management.tracing.sampling.probability=1",
        "management.otlp.tracing.export.enabled=false",
        "stove.payment.refund-execution.enabled=true", "stove.payment.refund-lookup.enabled=true", "stove.payment.refund-retry.enabled=true"})
class RefundWorkerTracingIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant BASE = Payment.now();
    private static final String NO_PARENT = "0000000000000000";
    private static final HttpServer PG;
    static {
        try {
            PG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PG.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> PG.stop(0)));
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void provider(DynamicPropertyRegistry registry) {
        registry.add("stove.payment.approval.order-service-key", () -> "fixture-order-service-key");
        registry.add("stove.payment.approval.payload-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("stove.payment.approval.order-base-url", () -> "http://127.0.0.1:1");
        registry.add("stove.payment.portone.base-url", () -> "http://127.0.0.1:" + PG.getAddress().getPort());
        registry.add("stove.payment.portone.api-secret", () -> "fixture-only-api-secret");
        registry.add("stove.payment.portone.store-id", () -> STORE);
        registry.add("stove.payment.portone.channel-key", () -> CHANNEL);
        registry.add("stove.payment.portone.webhook-secret", () -> SECRET);
        registry.add("stove.payment.portone.webhook-url", () -> "https://fixture.example/webhook");
    }
    @Autowired PaymentRepository payments;
    @Autowired RefundExecutionRepository executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired RecordingExporter exporter;
    @Autowired @Qualifier("refundRetryExecutor") ThreadPoolTaskExecutor retryExecutor;
    @Autowired @Qualifier("refundLookupExecutor") ThreadPoolTaskExecutor lookupExecutor;
    @MockitoBean Clock clock;
    private final List<String> posts = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> httpFailure = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger unanswered = new AtomicInteger();
    private volatile Mode mode;
    private RefundExecutionRequest input;
    private Payment paid;

    @BeforeEach void initialize() {
        clearDatabase(); when(clock.instant()).thenReturn(BASE);
        mode = Mode.SUCCESS; exporter.clear();
        PG.createContext("/payments/", this::respond);
    }
    @AfterEach void finish() {
        listeners.stop();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> retryExecutor.getActiveCount() == 0 && lookupExecutor.getActiveCount() == 0);
        PG.removeContext("/payments/");
        assertThat(httpFailure.get()).isNull();
        clearDatabase();
    }
    private void clearDatabase() {
        listeners.stop();
        jdbc.update("DELETE FROM refund_execution ORDER BY id DESC");
        jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }

    @DisplayName("[D-050] 응답 유실 뒤 조회 작업자의 PG 조회와 확정 Outbox가 저장된 환불 부모 아래 이어진다")
    @Test void theLookupWorkerConfirmsALostResponseUnderTheSavedRefundParent() throws Exception {
        mode = Mode.UNKNOWN;
        String parent = parent();
        paidRequest(); listeners.start(); awaitConsumed(send(parent));
        awaitStatus(execution -> execution.getLookupSchedule().getNextAt() != null && posts.size() == 1 && unanswered.get() >= 1);
        Instant switched = Instant.now();
        cancelled.set(true); mode = Mode.SUCCESS; when(clock.instant()).thenReturn(BASE.plusSeconds(5));
        awaitStatus(execution -> execution.getRefundConfirmedAt() != null);
        assertThat(posts).hasSize(1);
        assertContinuesUnder(parent, switched);
    }

    @DisplayName("[D-050] PG 5xx 뒤 재송신 작업자의 PG 취소와 확정 Outbox가 저장된 환불 부모 아래 이어진다")
    @Test void theRetryWorkerResendsUnderTheSavedRefundParent() throws Exception {
        mode = Mode.UNKNOWN;
        String parent = parent();
        paidRequest(); listeners.start(); awaitConsumed(send(parent));
        awaitStatus(execution -> BASE.plusSeconds(10).equals(execution.getCancellationSchedule().getNextAt()) && posts.size() == 1);
        // Same 10/30/60 s waits as RefundEventDispatchIntegrationTest: two unanswered retry rounds, then a resend that succeeds.
        when(clock.instant()).thenReturn(BASE.plusSeconds(10));
        awaitStatus(execution -> BASE.plusSeconds(40).equals(execution.getCancellationSchedule().getNextAt()));
        when(clock.instant()).thenReturn(BASE.plusSeconds(40));
        awaitStatus(execution -> BASE.plusSeconds(100).equals(execution.getCancellationSchedule().getNextAt()));
        Instant switched = Instant.now();
        mode = Mode.SUCCESS; when(clock.instant()).thenReturn(BASE.plusSeconds(100));
        awaitStatus(execution -> execution.getStatus() == RefundExecutionStatus.SUCCEEDED);
        assertThat(posts).hasSize(2);
        assertContinuesUnder(parent, switched);
    }

    @DisplayName("저장된 부모가 없는 환불은 작업자가 새 root에서 확정하고 다른 trace를 상속하지 않는다")
    @Test void aRefundWithoutSavedParentIsConfirmedUnderANewRoot() throws Exception {
        mode = Mode.UNKNOWN;
        paidRequest(); listeners.start(); awaitConsumed(send(null));
        assertThat(jdbc.queryForObject("SELECT trace_parent FROM refund_execution_trace WHERE attempt_id=?", String.class, input.attemptId())).isNull();
        awaitStatus(execution -> execution.getLookupSchedule().getNextAt() != null && posts.size() == 1 && unanswered.get() >= 1);
        Instant switched = Instant.now();
        cancelled.set(true); mode = Mode.SUCCESS; when(clock.instant()).thenReturn(BASE.plusSeconds(5));
        awaitStatus(execution -> execution.getRefundConfirmedAt() != null);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(workerCalls(switched)).isNotEmpty());
        List<SpanData> consumers = exporter.spans().stream().filter(s -> s.getKind() == SpanKind.CONSUMER).toList();
        for (SpanData call : workerCalls(switched)) {
            assertThat(consumers).noneMatch(consumer -> consumer.getTraceId().equals(call.getTraceId()));
            SpanData root = root(call);
            assertThat(root.getParentSpanId()).isEqualTo(NO_PARENT);
        }
    }

    private void assertContinuesUnder(String parent, Instant switched) {
        String trace = parent.substring(3, 35);
        String confirmed = jdbc.queryForObject("SELECT trace_parent FROM outbox_event WHERE event_type=?", String.class, EventType.REFUND_PAYMENT_CONFIRMED);
        assertThat(confirmed).as("RefundPaymentConfirmed trace_parent").isNotNull().startsWith("00-" + trace + "-");
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(workerCalls(switched)).as("worker PG calls under the saved refund trace")
                    .filteredOn(call -> call.getTraceId().equals(trace)).isNotEmpty();
            assertThat(pgCalls()).allSatisfy(call -> assertThat(call.getTraceId()).as(call.getName() + " " + call.getAttributes()).isEqualTo(trace));
        });
    }

    private String parent() { return "00-" + UUID.randomUUID().toString().replace("-", "") + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01"; }
    private List<SpanData> pgCalls() {
        return exporter.spans().stream().filter(s -> s.getKind() == SpanKind.CLIENT
                && s.getAttributes().asMap().values().stream().anyMatch(value -> String.valueOf(value).contains("/payments/" + input.paymentId()))).toList();
    }
    private List<SpanData> workerCalls(Instant after) {
        long nanos = after.getEpochSecond() * 1_000_000_000L + after.getNano();
        return pgCalls().stream().filter(s -> s.getStartEpochNanos() >= nanos).toList();
    }
    private SpanData root(SpanData span) {
        SpanData current = span;
        while (!current.getParentSpanId().equals(NO_PARENT)) {
            SpanData next = null;
            for (SpanData candidate : exporter.spans()) if (candidate.getSpanId().equals(current.getParentSpanId())) next = candidate;
            if (next == null) return current;
            current = next;
        }
        return current;
    }
    private void awaitStatus(Predicate<RefundExecution> condition) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> executions.findByAttemptId(input.attemptId()).filter(condition).isPresent());
        assertThat(httpFailure.get()).isNull();
    }
    private void paidRequest() {
        paid = Payment.receive(request(), "PORTONE_TEST");
        paid.confirm(new PgResult("PORTONE_TEST", paid.getPaymentId(), paid.getOrderNo(), paid.getAmount(), "KRW",
                "pg-" + paid.getPaymentId(), BASE.minusSeconds(60), PgResult.Decision.APPROVED, null, null, null), id(), BASE.minusSeconds(30));
        payments.saveAndFlush(paid);
        input = execution(paid);
    }
    private RecordMetadata send(String traceParent) throws Exception {
        DomainEvent event = event(input);
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), 0, event.partitionKey(), mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
        if (traceParent != null) record.headers().add(EventHeaders.TRACE_PARENT, traceParent.getBytes(StandardCharsets.UTF_8));
        return kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }
    /** The listener commits only after the first POST and its inline lookup returned (RefundEventDispatchIntegrationTest). */
    private void awaitConsumed(RecordMetadata sent) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(committedOffset(sent)).isGreaterThan(sent.offset()));
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

    private void respond(HttpExchange exchange) {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                if (mode == Mode.UNKNOWN && !posts.isEmpty()) { unanswered.incrementAndGet(); reply(exchange, 503, "{}"); return; }
                reply(exchange, 200, paymentBody()); return;
            }
            exchange.getRequestBody().readAllBytes();
            posts.add(input.attemptId());
            if (mode == Mode.UNKNOWN) { reply(exchange, 503, "{}"); return; }
            cancelled.set(true);
            reply(exchange, 200, mapper.writeValueAsString(Map.of("cancellation", cancellationRow())));
        } catch (Throwable failure) { httpFailure.compareAndSet(null, failure); exchange.close(); }
    }
    private String paymentBody() throws Exception {
        boolean done = cancelled.get();
        HashMap<String, Object> body = new HashMap<>();
        body.put("id", input.paymentId()); body.put("storeId", STORE); body.put("version", "V2"); body.put("currency", "KRW");
        body.put("status", done ? "CANCELLED" : "PAID");
        body.put("channel", Map.of("type", "TEST", "key", CHANNEL, "pgProvider", "TOSSPAY_V2"));
        body.put("amount", Map.of("total", input.amount(), "paid", input.amount(), "discount", 0, "cancelled", done ? input.amount() : 0));
        body.put("pgTxId", paid.getProviderTransactionId()); body.put("paidAt", paid.getProviderOccurredAt().toString());
        body.put("customData", mapper.writeValueAsString(Map.of("orderNo", input.orderNo())));
        body.put("cancellations", done ? List.of(cancellationRow()) : List.of());
        if (done) body.put("cancelledAt", BASE.toString());
        return mapper.writeValueAsString(body);
    }
    private Map<String, Object> cancellationRow() {
        return Map.of("id", "cancel-" + input.attemptId(), "status", "SUCCEEDED", "totalAmount", input.amount(),
                "taxFreeAmount", 0, "vatAmount", 0, "reason", "ESD refund " + input.refundId(), "requestedAt", BASE.toString(), "cancelledAt", BASE.toString());
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private enum Mode { SUCCESS, UNKNOWN }

    @TestConfiguration(proxyBeanMethods = false)
    static class TracingConfiguration {
        @Bean RecordingExporter recordingExporter() { return new RecordingExporter(); }
    }

    static class RecordingExporter implements SpanExporter {
        private final List<SpanData> recorded = new CopyOnWriteArrayList<>();
        List<SpanData> spans() { return List.copyOf(recorded); }
        void clear() { recorded.clear(); }
        @Override public CompletableResultCode export(Collection<SpanData> spans) { recorded.addAll(spans); return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
