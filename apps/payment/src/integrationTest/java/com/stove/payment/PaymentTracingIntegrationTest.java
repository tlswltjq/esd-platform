package com.stove.payment;

import static com.stove.payment.PaymentFixtures.event;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.adapter.pg.SimulatedPgHttpGateway;
import com.stove.payment.api.listener.OrderEventListener;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentOrderRequest;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PaymentTraceContexts;
import com.stove.payment.core.port.PaymentTracing;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureObservability
@Import(PaymentTracingIntegrationTest.TracingConfiguration.class)
@TestPropertySource(properties = {"management.tracing.sampling.probability=1", "management.otlp.tracing.export.enabled=false"})
class PaymentTracingIntegrationTest extends PaymentIntegrationTestBase {
    @Autowired OrderEventListener listener;
    @Autowired ReceiveOrderPayment receive;
    @Autowired ResolvePayment resolve;
    @Autowired PaymentRepository payments;
    @Autowired OutboxEventRepository events;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Tracer tracer;
    @Autowired Propagator propagator;
    @Autowired RecordingExporter exporter;
    @Autowired SdkTracerProvider provider;
    @Autowired PaymentTracing tracing;
    @MockitoSpyBean SimulatedPgHttpGateway gateway;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoSpyBean PaymentTraceContexts contexts;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        assertThat(tracer).isInstanceOf(OtelTracer.class);
        assertNoScope();
    }

    @Test void twoReceiptsKeepTheirOriginalTracesAcrossThreadAndBatchBoundary() throws Exception {
        PaymentOrderRequest a = request();
        PaymentOrderRequest b = request();
        String traceA = receiveUnderRequest(a);
        String traceB = receiveUnderRequest(b);
        assertThat(traceA).isNotEqualTo(traceB);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> completed = worker.submit(() -> {
                resolve.execute();
                assertNoScope();
            });
            completed.get(25, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
        assertResult(a, traceA);
        assertResult(b, traceB);
        assertAttempts(a, traceA, 1);
        assertAttempts(b, traceB, 1);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(exporter.spans().stream().filter(s -> s.getKind() == SpanKind.CLIENT
                        && s.getTraceId().equals(traceA)).count()).isGreaterThanOrEqualTo(2));
        assertNoScope();
    }

    @Test void duplicateReceiptKeepsFirstParentAndReceiptTransactionRollsBackTogether() throws Exception {
        PaymentOrderRequest input = request();
        String firstTrace = receiveUnderRequest(input);
        receiveUnderRequest(input);
        assertThat(parent(input)).startsWith("00-" + firstTrace + "-");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            receive.execute(request(), PaymentTraceContext.NONE);
            tx.setRollbackOnly();
        });
        assertThat(count("purchase_payment")).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("payment_request_trace")).isEqualTo(1);
    }

    @Test void metadataStorageFailureRollsBackRequestAndInbox() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("metadata storage failed");
        }).when(contexts).save(anyString(), any(PaymentTraceContext.class), any(Instant.class));
        assertThatThrownBy(() -> receive.execute(request(), PaymentTraceContext.NONE)).isInstanceOf(IllegalStateException.class);
        assertThat(count("purchase_payment")).isZero();
        assertThat(count("processed_event")).isZero();
        assertThat(count("payment_request_trace")).isZero();
    }

    @Test void concurrentReceiptsPreserveTheFirstCommittedParent() throws Exception {
        PaymentOrderRequest input = request();
        List<String> parents = List.of("00-11111111111111111111111111111111-1111111111111111-01",
                "00-22222222222222222222222222222222-2222222222222222-01");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> completed = new ArrayList<>();
            for (String value : parents) {
                completed.add(workers.submit(() -> {
                    start.await();
                    PaymentTraceContext context = new PaymentTraceContext(value);
                    try { receive.execute(input, context); }
                    catch (RuntimeException race) { receive.execute(input, context); }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : completed) result.get(25, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        String first = parent(input);
        assertThat(first).isIn(parents);
        receive.execute(input, PaymentTraceContext.NONE);
        assertThat(parent(input)).isEqualTo(first);
        assertThat(count("purchase_payment")).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("payment_request_trace")).isEqualTo(1);
    }

    @Test void storedParentOverridesAmbientScopeAndMissingParentsStartIndependentRoots() throws Exception {
        PaymentOrderRequest traced = request();
        PaymentOrderRequest untraced = request();
        String trace = receiveUnderRequest(traced);
        receive.execute(untraced);
        Span ambient = tracer.nextSpan().name("test.unrelated.scheduler").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(ambient)) {
            resolve.execute();
            assertRestored(ambient);
        } finally {
            ambient.end();
        }
        assertResult(traced, trace);
        String independent = result(untraced).getTraceParent().substring(3, 35);
        assertThat(independent).isNotEqualTo(trace).isNotEqualTo(ambient.context().traceId());
        assertAttempts(traced, trace, 1);
        assertAttempts(untraced, independent, 1);
        assertNoScope();
    }

    @Test void legacyPendingPaymentWithoutMetadataStillCompletes() {
        PaymentOrderRequest input = request();
        Payment payment = receive.execute(input);
        jdbc.update("DELETE FROM payment_request_trace WHERE payment_id=?", payment.getPaymentId());
        resolve.execute(payment.getPaymentId());
        assertThat(stored(input).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(result(input).getTraceParent()).isNotNull();
        assertNoScope();
    }

    @Test void failedFirstPaymentDoesNotLeakIntoTheNextPaymentOrTheAmbientScope() throws Exception {
        PaymentOrderRequest a = request();
        PaymentOrderRequest b = request();
        String traceA = receiveUnderRequest(a);
        String traceB = receiveUnderRequest(b);
        doAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo(tracer.currentSpan().context().traceId());
            if (invocation.getArgument(0, PgChargeRequest.class).paymentId().equals(stored(a).getPaymentId())) {
                throw new PgUnavailable("first payment unavailable");
            }
            return invocation.callRealMethod();
        }).when(gateway).charge(any());
        Span ambient = tracer.nextSpan().name("test.outer").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(ambient)) {
            resolve.execute();
            assertRestored(ambient);
        } finally {
            ambient.end();
        }
        assertThat(stored(a).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertResult(b, traceB);
        assertAttempts(a, traceA, 1);
        assertAttempts(b, traceB, 1);
        assertNoScope();
    }

    @Test void unsampledParentKeepsIdentityAndBusinessResultWithoutExportingProcessingSpan() throws Exception {
        PaymentOrderRequest input = request();
        String parent = "00-33333333333333333333333333333333-3333333333333333-00";
        deliver(input, parent);
        resolve.execute(stored(input).getPaymentId());
        assertThat(result(input).getTraceParent()).startsWith("00-33333333333333333333333333333333-").endsWith("-00");
        provider.forceFlush().join(10, TimeUnit.SECONDS);
        assertThat(exporter.spans()).noneMatch(s -> stored(input).getPaymentId().equals(s.getAttributes().get(AttributeKey.stringKey("payment.id"))));
        assertNoScope();
    }

    @Test void lostPgResponseAndResultRollbackKeepOriginalParentForEveryRetry() throws Exception {
        PaymentOrderRequest input = request();
        String trace = receiveUnderRequest(input);
        String paymentId = stored(input).getPaymentId();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new PgUnavailable("response lost after PG commit");
        }).when(gateway).charge(any());
        resolve.execute(paymentId);
        assertThat(stored(input).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(events.count()).isZero();
        reset(gateway);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new IllegalStateException("result rollback");
                }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        resolve.execute(paymentId);
        assertThat(stored(input).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(events.count()).isZero();
        reset(outbox);
        resolve.execute(paymentId);
        assertResult(input, trace);
        assertAttempts(input, trace, 3);
        assertThat(ExternalPgProcess.JDBC.queryForObject("SELECT COUNT(*) FROM pg_charge WHERE payment_id=?", Integer.class, paymentId)).isEqualTo(1);
        assertNoScope();
    }

    @Test void outerFailureRestoresScopeWithoutSwallowingBusinessErrors() throws Exception {
        PaymentOrderRequest input = request();
        String trace = receiveUnderRequest(input);
        String paymentId = stored(input).getPaymentId();
        Span ambient = tracer.nextSpan().name("test.outer.failure").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(ambient)) {
            assertThatThrownBy(() -> tracing.run(contexts.find(paymentId), paymentId, () -> {
                assertThat(tracer.currentSpan().context().traceId()).isEqualTo(trace);
                throw new IllegalStateException("processing failed");
            })).isInstanceOf(IllegalStateException.class);
            assertRestored(ambient);
        } finally {
            ambient.end();
        }
        assertAttempts(input, trace, 1);
        assertThat(exporter.spans()).anySatisfy(span -> {
            assertThat(span.getAttributes().get(AttributeKey.stringKey("payment.id"))).isEqualTo(paymentId);
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        });
        assertNoScope();
    }

    private String receiveUnderRequest(PaymentOrderRequest input) throws Exception {
        Span requestSpan = tracer.nextSpan().name("test.order.request").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(requestSpan)) {
            Map<String, String> headers = new HashMap<>();
            propagator.inject(requestSpan.context(), headers, Map::put);
            deliver(input, headers.get(EventHeaders.TRACE_PARENT));
            return requestSpan.context().traceId();
        } finally {
            requestSpan.end();
        }
    }

    private void deliver(PaymentOrderRequest input, String parent) throws Exception {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.ORDER, 0, 0, input.orderNo(), mapper.writeValueAsString(event(input)));
        record.headers().add(EventHeaders.EVENT_ID, input.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, "OrderPlaced".getBytes(StandardCharsets.UTF_8));
        if (parent != null) record.headers().add(EventHeaders.TRACE_PARENT, parent.getBytes(StandardCharsets.UTF_8));
        listener.receive(record);
    }

    private Payment stored(PaymentOrderRequest input) { return payments.findByOrderNo(input.orderNo()).orElseThrow(); }
    private String parent(PaymentOrderRequest input) { return contexts.find(stored(input).getPaymentId()).traceParent(); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private OutboxEvent result(PaymentOrderRequest input) {
        return events.findAll().stream().filter(e -> e.getPartitionKey().equals(input.orderNo())).findFirst().orElseThrow();
    }
    private void assertResult(PaymentOrderRequest input, String trace) {
        assertThat(stored(input).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(result(input).getTraceParent()).startsWith("00-" + trace + "-");
    }
    private void assertAttempts(PaymentOrderRequest input, String trace, int count) {
        String id = stored(input).getPaymentId();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<SpanData> attempts = exporter.spans().stream().filter(s -> s.getName().equals("payment.process")
                    && id.equals(s.getAttributes().get(AttributeKey.stringKey("payment.id")))).toList();
            assertThat(attempts).hasSize(count).allSatisfy(span -> {
                assertThat(span.getTraceId()).isEqualTo(trace);
                assertThat(span.getParentSpanId()).isEqualTo(parent(input) == null ? "0000000000000000" : parent(input).substring(36, 52));
            });
            assertThat(attempts.stream().map(SpanData::getSpanId).distinct().count()).isEqualTo(count);
        });
    }
    private void assertNoScope() {
        assertThat(tracer.currentSpan()).isNull();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
    }
    private void assertRestored(Span ambient) {
        assertThat(tracer.currentSpan().context().spanId()).isEqualTo(ambient.context().spanId());
        assertThat(MDC.get("traceId")).isEqualTo(ambient.context().traceId());
        assertThat(MDC.get("spanId")).isEqualTo(ambient.context().spanId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TracingConfiguration {
        @Bean RecordingExporter recordingExporter() { return new RecordingExporter(); }
    }

    static class RecordingExporter implements SpanExporter {
        private final List<SpanData> recorded = new CopyOnWriteArrayList<>();
        List<SpanData> spans() { return List.copyOf(recorded); }
        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            recorded.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
