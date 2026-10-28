package com.stove.order;

import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.envelope;
import static com.stove.order.OrderFixtures.failed;
import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static com.stove.order.OrderFixtures.product;
import static com.stove.order.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.kafka.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.kafka.contract.payload.OrderPaymentFailedEvent;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.common.kafka.contract.payload.LicenseGrantedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.api.listener.PaymentResultMapper;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderErrorCode;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyPaymentResult;
import com.stove.order.core.service.CreateOrder;
import com.stove.order.core.service.ReadOrder;
import com.stove.order.core.service.SearchOrders;
import com.stove.order.core.service.RequestRefund;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class OrderIntegrationTest extends OrderIntegrationTestBase {
    @Autowired CreateOrder create;
    @Autowired ReadOrder read;
    @Autowired SearchOrders search;
    @Autowired ApplyPaymentResult apply;
    @Autowired RequestRefund requestRefund;
    @Autowired RefundRepository refunds;
    @MockitoSpyBean OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CatalogProducts catalog;
    @Autowired OutboxEventRepository outboxRows;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoSpyBean ProcessedEventGuard guard;
    @LocalServerPort int port;

    @BeforeEach void clean() {
        listeners.stop();
        jdbc.update("DELETE FROM order_refund_attempt");
        jdbc.update("DELETE FROM order_license_grant"); jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
        when(catalog.detail(anyString())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return product();
        });
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private Order saved(String buyer) { return create.execute(buyer, request()); }
    private Order stored(Order o) { return orders.findById(o.getId()).orElseThrow(); }
    record Response(int status, JsonNode body) { JsonNode data() { return body.path("data"); } }
    private Response http(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec spec = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) spec.header("Authorization", "Bearer " + bearer);
        if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((r, s) -> new Response(s.getStatusCode().value(), mapper.readTree(s.getBody())));
    }
    private Map<String,Object> body(OrderRequest r) {
        return Map.of("requestId", r.requestId(), "productCode", r.productCode(),
                "expectedContentVersion", r.expectedContentVersion(), "expectedAmount", r.expectedAmount());
    }
    private void accept(OrderPaymentCompletedEvent e) { apply.execute(e.eventType(), PaymentResultMapper.from(envelope(e), e)); }
    private void accept(OrderPaymentFailedEvent e) { apply.execute(e.eventType(), PaymentResultMapper.from(envelope(e), e)); }

    @Test void savesSnapshotAndOneEventAndReusesRequestWithoutCatalog() throws Exception {
        OrderRequest input = request(); Order o = create.execute("usr_buyer", input);
        assertThat(count("purchase_order")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event", String.class);
        OrderPlacedEvent event = mapper.readValue(payload, OrderPlacedEvent.class);
        assertThat(event.eventId()).isEqualTo(o.getRequestEventId());
        assertThat(event.orderNo()).isEqualTo(o.getOrderNo());
        assertThat(event.listAmount()).isEqualTo(10000); assertThat(event.discountAmount()).isZero();
        assertThat(event.amount()).isEqualTo(10000); assertThat(event.contentVersion()).isEqualTo(3);
        when(catalog.detail(anyString())).thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));
        accept(completed(o));
        assertThat(create.execute("usr_buyer", input).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(() -> create.execute("usr_buyer", new OrderRequest(input.requestId(), input.productCode(), 3, 1)))
                .isInstanceOf(BusinessException.class);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(paidEvents()).isEqualTo(1);
        assertThatThrownBy(() -> create.execute("usr_buyer", request())).isInstanceOf(BusinessException.class);
        assertThat(read.execute(o.getOrderNo(), "usr_buyer").getTitle()).isEqualTo("한글 게임");
    }
    @Test void newPublicationDoesNotRewriteOrderTerms() {
        Order first = saved("usr_buyer");
        when(catalog.detail(anyString())).thenReturn(new PublishedProduct(1L, "Game-1", "New title", 4, 20000, "KRW"));
        assertThatThrownBy(() -> create.execute("usr_buyer", request())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> create.execute("usr_buyer", new OrderRequest(id(), "Game-1", 4, 20000)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.PURCHASE_IN_PROGRESS));
        Order next = create.execute("usr_other", new OrderRequest(id(), "Game-1", 4, 20000));
        assertThat(stored(first).getTitle()).isEqualTo("한글 게임");
        assertThat(stored(first).getAmount()).isEqualTo(10000);
        assertThat(next.getTitle()).isEqualTo("New title");
    }
    @Test void pendingAndPaidBlockNewRequestsWithoutCreatingAnotherPaymentRequest() {
        OrderRequest input = request(); Order first = create.execute("usr_buyer", input);
        Response pending = http("POST", "/api/v1/orders", body(request()), token("usr_buyer", "MEMBER"));
        assertThat(pending.status()).isEqualTo(409);
        assertThat(pending.body().toString()).contains("PURCHASE_IN_PROGRESS");
        accept(completed(first));
        Response paid = http("POST", "/api/v1/orders", body(request()), token("usr_buyer", "MEMBER"));
        assertThat(paid.status()).isEqualTo(409);
        assertThat(paid.body().toString()).contains("PRODUCT_ALREADY_PURCHASED");
        assertThat(create.execute("usr_buyer", input).getOrderNo()).isEqualTo(first.getOrderNo());
        assertThat(count("purchase_order")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
    }
    @Test void onlyCommittedDefinitiveFailureReleasesTheProductForANewRequest() {
        OrderRequest input = request(); Order first = create.execute("usr_buyer", input);
        OrderPaymentFailedEvent result = failed(first);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            accept(result);
            assertThat(stored(first).getStatus()).isEqualTo(OrderStatus.FAILED);
            throw new IllegalStateException("abort after failure flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saved("usr_buyer")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.PURCHASE_IN_PROGRESS));
        accept(result);
        Order next = saved("usr_buyer");
        assertThat(next.getOrderNo()).isNotEqualTo(first.getOrderNo());
        assertThat(next.getRequestEventId()).isNotEqualTo(first.getRequestEventId());
        assertThat(create.execute("usr_buyer", input).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(count("purchase_order")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(2);
    }
    @Test void uncommittedFailureDoesNotPermitACompetingPurchase() throws Exception {
        Order first = saved("usr_buyer");
        CountDownLatch flushed = new CountDownLatch(1); CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                accept(failed(first)); flushed.countDown();
                try { assertThat(commit.await(15, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(flushed.await(15, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> saved("usr_buyer")).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.PURCHASE_IN_PROGRESS));
            commit.countDown(); result.get(20, TimeUnit.SECONDS);
            assertThat(saved("usr_buyer").getStatus()).isEqualTo(OrderStatus.PENDING);
        } finally { commit.countDown(); pool.shutdownNow(); }
    }
    @Test void changedCatalogIdentityCannotBypassThePurchaseGuard() {
        saved("usr_buyer");
        when(catalog.detail("Game-1")).thenReturn(new PublishedProduct(2L, "Game-1", "Changed ID", 3, 10000, "KRW"));
        assertThatThrownBy(() -> saved("usr_buyer")).isInstanceOf(BusinessException.class);
        when(catalog.detail("Game-2")).thenReturn(new PublishedProduct(1L, "Game-2", "Changed code", 3, 10000, "KRW"));
        assertThatThrownBy(() -> create.execute("usr_buyer", new OrderRequest(id(), "Game-2", 3, 10000)))
                .isInstanceOf(BusinessException.class);
        assertThat(count("purchase_order")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void unavailableMalformedMissingAndFreeProductsLeaveNoOrderOrOutbox() {
        for (BusinessException error : new BusinessException[]{new BusinessException(ErrorCode.NOT_FOUND),
                new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE), new BusinessException(OrderErrorCode.CATALOG_INVALID_RESPONSE)}) {
            doThrow(error).when(catalog).detail(anyString());
            assertThatThrownBy(() -> saved("usr_buyer")).isSameAs(error);
        }
        doReturn(null).when(catalog).detail(anyString());
        assertThatThrownBy(() -> saved("usr_buyer")).isInstanceOf(BusinessException.class);
        when(catalog.detail(anyString())).thenReturn(new PublishedProduct(1L, "Game-1", "Free", 3, 0, "KRW"));
        assertThatThrownBy(() -> create.execute("usr_buyer", new OrderRequest(id(), "Game-1", 3, 0)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode().statusCode()).isEqualTo(409));
        assertThat(count("purchase_order")).isZero(); assertThat(count("outbox_event")).isZero();
    }
    @Test void outboxFailureRollsBackAnAlreadyInsertedOrderAndEvent() {
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("relay record failed"); })
                        .when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        try {
            assertThatThrownBy(() -> saved("usr_buyer")).isInstanceOf(IllegalStateException.class);
            assertThat(count("purchase_order")).isZero(); assertThat(count("outbox_event")).isZero();
        } finally { reset(outbox); }
    }
    @Test void sameRequestRaceCreatesExactlyOneOrderAndEvent() throws Exception {
        OrderRequest input = request(); CyclicBarrier barrier = new CyclicBarrier(4);
        when(catalog.detail(anyString())).thenAnswer(inv -> { barrier.await(10, TimeUnit.SECONDS); return product(); });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            ArrayList<Future<String>> futures = new ArrayList<Future<String>>();
            for (int i=0;i<4;i++) futures.add(pool.submit(() -> create.execute("usr_buyer", input).getOrderNo()));
            HashSet<String> numbers = new HashSet<String>();
            for (Future<String> f : futures) numbers.add(f.get(25, TimeUnit.SECONDS));
            assertThat(numbers).hasSize(1);
            assertThat(count("purchase_order")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void differentRequestRaceHitsTheDatabaseGuardAndCreatesOneOrderAndEvent() throws Exception {
        CyclicBarrier inserted = new CyclicBarrier(4);
        doAnswer(inv -> {
            inserted.await(15, TimeUnit.SECONDS);
            return mockingDetails(orders).getMockCreationSettings().getDefaultAnswer().answer(inv);
        }).when(orders).saveAndFlush(any(Order.class));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                try { create.execute("usr_buyer", request()); return "created"; }
                catch (BusinessException e) { return e.errorCode().code(); }
            }));
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("created", "PURCHASE_IN_PROGRESS", "PURCHASE_IN_PROGRESS", "PURCHASE_IN_PROGRESS");
            assertThat(count("purchase_order")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        } finally { reset(orders); pool.shutdownNow(); }
    }
    @Test void unrelatedIntegrityFailureIsNotReportedAsAnExistingPurchase() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("unrelated constraint");
        doThrow(failure).when(orders).saveAndFlush(any(Order.class));
        assertThatThrownBy(() -> saved("usr_buyer")).isSameAs(failure);
        assertThat(count("purchase_order")).isZero(); assertThat(count("outbox_event")).isZero();
    }
    @Test void concurrentCommitIsRecoveredEvenWhenTheFirstCatalogCallFails() throws Exception {
        OrderRequest input = request(); CountDownLatch entered = new CountDownLatch(1); CountDownLatch committed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(catalog.detail(anyString())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
            }
            return product();
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Order> waiting = pool.submit(() -> create.execute("usr_buyer", input));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Order winner = create.execute("usr_buyer", input); committed.countDown();
            assertThat(waiting.get(15, TimeUnit.SECONDS).getOrderNo()).isEqualTo(winner.getOrderNo());
            assertThat(count("outbox_event")).isEqualTo(1);
        } finally { committed.countDown(); pool.shutdownNow(); }
    }
    @Test void requestKeysAndBuyerSubjectsUseExactCaseAndSeparateOwners() {
        OrderRequest request = new OrderRequest("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "Game-1", 3, 10000);
        Order first = create.execute("usr_buyer", request); create.execute("USR_BUYER", request);
        accept(failed(first));
        create.execute("usr_buyer", new OrderRequest(request.requestId().toUpperCase(Locale.ROOT), "Game-1", 3, 10000));
        assertThat(count("purchase_order")).isEqualTo(3);
    }
    @Test void appliesSuccessAndFailureAndKeepsStableResultOnRedelivery() {
        Order a = saved("usr_buyer"); OrderPaymentCompletedEvent event = completed(a); accept(event);
        Instant updated = stored(a).getUpdatedAt(); accept(event);
        assertThat(stored(a).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stored(a).getUpdatedAt()).isEqualTo(updated);
        Order b = saved("usr_other"); OrderPaymentFailedEvent failure = failed(b); accept(failure); accept(failure);
        assertThat(stored(b).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored(b).getFailureReasonCode()).isEqualTo("DECLINED");
        assertThat(count("processed_event")).isEqualTo(2);
    }
    private int paidEvents() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderPaid'", Integer.class); }
    @Test void paidEventUsesStoredOrderFactsAndOnlyFirstSuccessfulApplicationPublishes() throws Exception {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent result = completed(o); accept(result);
        String original = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='OrderPaid'", String.class);
        OrderPaidEvent event = mapper.readValue(original, OrderPaidEvent.class);
        Order paid = stored(o);
        assertThat(event.occurredAt()).isEqualTo(paid.getUpdatedAt());
        assertThat(event.orderNo()).isEqualTo(o.getOrderNo()); assertThat(event.requestEventId()).isEqualTo(o.getRequestEventId());
        assertThat(event.paymentResultEventId()).isEqualTo(result.eventId()); assertThat(event.paymentId()).isEqualTo(result.paymentId());
        assertThat(event.buyerSubject()).isEqualTo(o.getBuyerSubject()); assertThat(event.productId()).isEqualTo(o.getProductId());
        assertThat(event.productCode()).isEqualTo(o.getProductCode()); assertThat(event.title()).isEqualTo(o.getTitle());
        assertThat(event.contentVersion()).isEqualTo(o.getContentVersion()); assertThat(event.amount()).isEqualTo(o.getAmount());
        assertThat(event.currency()).isEqualTo(o.getCurrency()); assertThat(event.topic()).isEqualTo(Topics.ORDER);
        accept(result); accept(result); assertThat(paidEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='OrderPaid'", String.class)).isEqualTo(original);
        Order failed = saved("usr_failed"); accept(failed(failed)); assertThat(paidEvents()).isEqualTo(1);
    }
    @Test void paidOutboxFailureRollsBackOrderAndInboxAndFreshDeliveryRecovers() {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent e = completed(o);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("after paid Outbox"); })
                        .when(outbox).record(eq("Order"), eq(o.getOrderNo()), any(OrderPaidEvent.class)));
        try {
            assertThatThrownBy(() -> accept(e)).isInstanceOf(IllegalStateException.class);
            assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PENDING); assertThat(count("processed_event")).isZero();
            assertThat(paidEvents()).isZero();
        } finally { reset(outbox); }
        accept(e); assertThat(paidEvents()).isEqualTo(1);
    }
    @Test void historicalPaidOrderDoesNotBackfillOnReadOrDuplicateResult() {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent e = completed(o); accept(e);
        jdbc.update("DELETE FROM outbox_event WHERE event_type='OrderPaid'");
        read.execute(o.getOrderNo(), o.getBuyerSubject()); accept(e); assertThat(paidEvents()).isZero();
    }
    @Test void concurrentSamePaymentResultPublishesOnePaidEvent() throws Exception {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent e = completed(o);
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(pool.submit(() -> { start.await(); accept(e); return null; }),
                    pool.submit(() -> { start.await(); accept(e); return null; }));
            start.countDown(); for (Future<?> f : futures) f.get(25, TimeUnit.SECONDS);
            assertThat(paidEvents()).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void deadPaidOutboxRequeuePreservesOriginalIdAndPayload() {
        Order o = saved("usr_buyer"); accept(completed(o));
        Map<String, Object> original = jdbc.queryForMap("SELECT event_id,payload FROM outbox_event WHERE event_type='OrderPaid'");
        jdbc.update("UPDATE outbox_event SET status='DEAD',retry_count=10 WHERE event_type='OrderPaid'");
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                outboxRows.findAll().stream().filter(row -> row.getEventId().equals(original.get("event_id")))
                        .findFirst().orElseThrow().requeue());
        assertThat(jdbc.queryForMap("SELECT event_id,payload FROM outbox_event WHERE event_type='OrderPaid'")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_type='OrderPaid'", String.class)).isEqualTo("PENDING");
    }
    @Test void mismatchedConnectionsAndFeeDeductedAmountsRollBackInboxAndState() {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent good = completed(o);
        for (OrderPaymentCompletedEvent bad : new OrderPaymentCompletedEvent[]{
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), "wrong", good.paymentId(), o.getBuyerSubject(), 10000, "KRW"),
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), o.getRequestEventId(), good.paymentId(), "other", 10000, "KRW"),
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), o.getRequestEventId(), good.paymentId(), o.getBuyerSubject(), 9800, "KRW"),
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), o.getRequestEventId(), good.paymentId(), o.getBuyerSubject(), 10000, "USD")})
            assertThatThrownBy(() -> accept(bad)).isInstanceOf(BusinessException.class);
        assertThat(count("processed_event")).isZero(); assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PENDING);
        accept(good);
    }
    @Test void invalidEnvelopeMissingOrderAndIncompleteFailureAreNotMarkedProcessed() {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent e = completed(o);
        assertThatThrownBy(() -> apply.execute(e.eventType(), PaymentResultMapper.from(new EventEnvelope("wrong", e.eventType(), e.orderNo(), ""), e))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> apply.execute(e.eventType(), PaymentResultMapper.from(new EventEnvelope(e.eventId(), e.eventType(), "wrong", ""), e))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(completed(order()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(new OrderPaymentFailedEvent(id(), e.occurredAt(), e.orderNo(), e.requestEventId(), e.paymentId(), e.buyerSubject(), e.amount(), e.currency(), "", null)))
                .isInstanceOf(BusinessException.class);
        assertThat(count("processed_event")).isZero();
    }
    @Test void rejectsChangedBodyNewFinalEventAndEventIdReuseAcrossOrders() {
        Order a = saved("usr_buyer"); Order b = saved("usr_other"); OrderPaymentCompletedEvent e = completed(a); accept(e);
        assertThatThrownBy(() -> accept(new OrderPaymentCompletedEvent(e.eventId(), e.occurredAt(), e.orderNo(), e.requestEventId(), "different-payment", e.buyerSubject(), e.amount(), e.currency())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(completed(a))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(failed(a))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(new OrderPaymentCompletedEvent(e.eventId(), e.occurredAt(), b.getOrderNo(), b.getRequestEventId(), id(), b.getBuyerSubject(), b.getAmount(), b.getCurrency())))
                .isInstanceOf(BusinessException.class);
        assertThat(stored(b).getStatus()).isEqualTo(OrderStatus.PENDING); assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void failureAfterInboxInsertRollsBackAndOriginalEventCanBeDeliveredAgain() {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent e = completed(o);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("after inbox"); })
                        .when(guard).firstDelivery(eq(e.eventId()), anyString(), anyString()));
        try {
            assertThatThrownBy(() -> accept(e)).isInstanceOf(IllegalStateException.class);
            assertThat(count("processed_event")).isZero(); assertThat(stored(o).paymentResult()).isNull();
        } finally { reset(guard); }
        accept(e); assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PAID);
    }
    @Test void rollbackAfterFlushingTheFinalStateAlsoRollsBackInbox() {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent e = completed(o);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            accept(e);
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, o.getId())).isEqualTo("PAID");
            assertThat(count("processed_event")).isEqualTo(1); assertThat(paidEvents()).isEqualTo(1);
            throw new IllegalStateException("commit aborted after state flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored(o).paymentResult()).isNull(); assertThat(count("processed_event")).isZero();
        assertThat(paidEvents()).isZero();
        accept(e); assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PAID);
    }
    @Test void simultaneousOppositeResultsAcceptExactlyOneTerminalFact() throws Exception {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent success = completed(o); OrderPaymentFailedEvent failure = failed(o);
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks = List.of(pool.submit(() -> { start.await(); try { accept(success); return true; } catch (BusinessException ex) { return false; } }),
                    pool.submit(() -> { start.await(); try { accept(failure); return true; } catch (BusinessException ex) { return false; } }));
            start.countDown(); int accepted = 0;
            for (Future<Boolean> f : tasks) if (f.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(1);
            assertThat(stored(o).getStatus()).isIn(OrderStatus.PAID, OrderStatus.FAILED);
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentReuseOfOneResultEventIdCannotFinalizeTwoOrders() throws Exception {
        Order a = saved("usr_buyer"); Order b = saved("usr_other"); OrderPaymentCompletedEvent e = completed(a);
        OrderPaymentCompletedEvent other = new OrderPaymentCompletedEvent(e.eventId(), e.occurredAt(), b.getOrderNo(), b.getRequestEventId(), id(), b.getBuyerSubject(), b.getAmount(), b.getCurrency());
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks = List.of(pool.submit(() -> { start.await(); try { accept(e); return true; } catch (RuntimeException ex) { return false; } }),
                    pool.submit(() -> { start.await(); try { accept(other); return true; } catch (RuntimeException ex) { return false; } }));
            start.countDown(); int accepted = 0;
            for (Future<Boolean> f : tasks) if (f.get(25, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(1);
            OrderPaymentCompletedEvent loser = stored(a).getStatus() == OrderStatus.PENDING ? e : other;
            assertThatThrownBy(() -> accept(loser)).isInstanceOf(BusinessException.class);
            assertThat(List.of(stored(a), stored(b))).filteredOn(o -> o.getStatus() == OrderStatus.PAID).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void onlyOwnedOrdersAreReturnedWithStablePagesAndNoInternalFields() {
        Order own = saved("usr_buyer"); saved("usr_other");
        when(catalog.detail("Game-2")).thenReturn(new PublishedProduct(2L, "Game-2", "Second game", 3, 10000, "KRW"));
        Order last = create.execute("usr_buyer", new OrderRequest(id(), "Game-2", 3, 10000));
        jdbc.update("UPDATE purchase_order SET created_at='2026-10-16 00:00:00'");
        assertThat(search.execute("usr_buyer", 0, 1).getContent().getFirst().getOrderNo()).isEqualTo(last.getOrderNo());
        assertThat(search.execute("usr_buyer", 1, 1).getTotalElements()).isEqualTo(2);
        String bearer = token("usr_buyer", "MEMBER");
        Response detail = http("GET", "/api/v1/orders/" + own.getOrderNo(), null, bearer);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().toString()).doesNotContain("buyerSubject", "requestEventId", "paymentResultEventId");
        assertThat(http("GET", "/api/v1/orders/" + own.getOrderNo(), null, token("usr_other", "MEMBER")).status()).isEqualTo(404);
        assertThat(http("GET", "/api/v1/orders/" + own.getOrderNo().toUpperCase(Locale.ROOT), null, bearer).status()).isEqualTo(404);
        for (String query : List.of("page=-1", "size=0", "size=101"))
            assertThat(http("GET", "/api/v1/orders?" + query, null, bearer).status()).isEqualTo(400);
    }
    @Test void authenticatesJwtAndIgnoresForgedOwnerAndStatus() {
        String bearer = token("usr_buyer", "MEMBER"); OrderRequest input = request();
        HashMap<String, Object> forged = new HashMap<String,Object>(body(input)); forged.put("buyerSubject", "usr_other"); forged.put("memberId", 999); forged.put("status", "PAID");
        Response created = http("POST", "/api/v1/orders", forged, bearer);
        assertThat(created.status()).isEqualTo(200); assertThat(created.data().path("status").asText()).isEqualTo("PENDING");
        assertThat(orders.findAll().getFirst().getBuyerSubject()).isEqualTo("usr_buyer");
        assertThat(http("GET", "/api/v1/orders", null, null).status()).isEqualTo(401);
        assertThat(http("POST", "/api/v1/orders", body(request()), token("usr_creator", "CREATOR")).status()).isEqualTo(403);
        for (String invalid : List.of(token("usr_buyer", "MEMBER", "https://wrong", "esd-api", Instant.now().plusSeconds(300)),
                token("usr_buyer", "MEMBER", ISSUER, "wrong-audience", Instant.now().plusSeconds(300)),
                token("usr_buyer", "MEMBER", ISSUER, "esd-api", Instant.now().minusSeconds(300)), bearer.substring(0, bearer.length()-8) + "badbadbad"))
            assertThat(http("GET", "/api/v1/orders", null, invalid).status()).isEqualTo(401);
        assertThat(http("POST", "/api/v1/orders/" + created.data().path("orderNo").asText() + "/cancel", null, bearer).status()).isEqualTo(403);
        assertThat(http("GET", "/api/v1/ops/dlt", null, bearer).status()).isEqualTo(403);
    }
    @Test void validatesRequiredIntegralNumbersAndInputLimits() {
        String bearer = token("usr_buyer", "MEMBER");
        for (String field : List.of("requestId", "productCode", "expectedContentVersion", "expectedAmount")) {
            HashMap<String, Object> invalid = new HashMap<String,Object>(body(request())); invalid.remove(field);
            assertThat(http("POST", "/api/v1/orders", invalid, bearer).status()).isEqualTo(400);
        }
        for (String field : List.of("expectedContentVersion", "expectedAmount")) {
            HashMap<String, Object> invalid = new HashMap<String,Object>(body(request())); invalid.put(field, 1.5);
            assertThat(http("POST", "/api/v1/orders", invalid, bearer).status()).isEqualTo(400);
        }
        HashMap<String, Object> invalid = new HashMap<String,Object>(body(request())); invalid.put("expectedAmount", -1);
        assertThat(http("POST", "/api/v1/orders", invalid, bearer).status()).isEqualTo(400);
        assertThat(count("purchase_order")).isZero();
    }
    @Test void kafkaRetriesInvalidResultToDltAndReplaysOriginalAfterOrderRecovery() throws Exception {
        Order o = saved("usr_buyer"); OrderPaymentCompletedEvent e = completed(o);
        // A missing request is recoverable by restoring its original Order row.
        jdbc.update("UPDATE purchase_order SET order_no=? WHERE id=?", id(), o.getId());
        HashMap<String, Object> config = new HashMap<String,Object>();
        config.put("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id", "order-dlt-" + id()); config.put("auto.offset.reset", "earliest");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<String,String>(config)) {
            consumer.subscribe(List.of(Topics.PAYMENT + ".DLT")); listeners.start();
            send(e);
            boolean found = false; long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (!found && System.nanoTime() < deadline)
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) if (record.value().contains(e.eventId())) found = true;
            assertThat(found).isTrue(); assertThat(count("processed_event")).isZero();
            jdbc.update("UPDATE purchase_order SET order_no=? WHERE id=?", o.getOrderNo(), o.getId());
            send(e); send(e);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PAID));
            assertThat(count("processed_event")).isEqualTo(1);
        } finally { listeners.stop(); }
    }
    private void send(DomainEvent e) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<String,String>(e.topic(), e.partitionKey(), mapper.writeValueAsString(e));
        record.headers().add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, e.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    @Test void aKafkaGrantConfirmationResumesTheWaitingRefundOnlyOnce() throws Exception {
        Order order = saved("usr_buyer");
        accept(completed(order));
        Order paid = stored(order);
        Refund received = requestRefund.execute(paid.getBuyerSubject(), paid.getOrderNo());
        assertThat(received.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        LicenseGrantedEvent event = new LicenseGrantedEvent(id(), paid.getResultOccurredAt(), id(),
                paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        try {
            listeners.start();
            send(event); send(event);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getStatus())
                        .isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseSuspensionRequested' AND partition_key=?",
                        Integer.class, paid.getOrderNo())).isEqualTo(1);
            });
        } finally { listeners.stop(); }
    }
    @Test void openApiMatchesSnapshotAndContainsEightBusinessOperations() throws Exception {
        OpenApiSnapshot.verify(port, "order");
        JsonNode paths = http("GET", "/v3/api-docs", null, null).body().path("paths");
        assertThat(paths.size()).isEqualTo(6);
        assertThat(paths.path("/api/v1/orders").has("post")).isTrue();
        assertThat(paths.path("/api/v1/orders").has("get")).isTrue();
        assertThat(paths.path("/api/v1/orders/{orderNo}").has("get")).isTrue();
        assertThat(paths.path("/api/v1/orders/{orderNo}/refund").has("post")).isTrue();
        assertThat(paths.path("/api/v1/orders/{orderNo}/refund").has("get")).isTrue();
        assertThat(paths.path("/api/v1/admin/refunds").has("get")).isTrue();
        assertThat(paths.path("/api/v1/admin/refunds/{refundId}").has("get")).isTrue();
        assertThat(paths.path("/api/v1/admin/refunds/{refundId}/decision").has("post")).isTrue();
    }
}
