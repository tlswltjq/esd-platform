package com.stove.order;

import com.stove.order.api.listener.PaymentResultMapper;
import static com.stove.order.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.stove.common.kafka.*;
import com.stove.common.kafka.contract.*;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.web.error.*;
import com.stove.order.core.domain.*;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class OrderIntegrationTest extends OrderIntegrationTestBase {
    @Autowired CreateOrder create;
    @Autowired ReadOrder read;
    @Autowired SearchOrders search;
    @Autowired ApplyPaymentResult apply;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean CatalogProducts catalog;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoSpyBean ProcessedEventGuard guard;
    @LocalServerPort int port;

    @BeforeEach void clean() {
        listeners.stop();
        jdbc.update("DELETE FROM purchase_order"); jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
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
        var spec = RestClient.create("http://127.0.0.1:" + port).method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
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
        var input = request(); var o = create.execute("usr_buyer", input);
        assertThat(count("purchase_order")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        var payload = jdbc.queryForObject("SELECT payload FROM outbox_event", String.class);
        var event = mapper.readValue(payload, OrderPlacedEvent.class);
        assertThat(event.eventId()).isEqualTo(o.getRequestEventId());
        assertThat(event.orderNo()).isEqualTo(o.getOrderNo());
        assertThat(event.listAmount()).isEqualTo(10000); assertThat(event.discountAmount()).isZero();
        assertThat(event.amount()).isEqualTo(10000); assertThat(event.contentVersion()).isEqualTo(3);
        when(catalog.detail(anyString())).thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));
        accept(completed(o));
        assertThat(create.execute("usr_buyer", input).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(() -> create.execute("usr_buyer", new OrderRequest(input.requestId(), input.productCode(), 3, 1)))
                .isInstanceOf(BusinessException.class);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThatThrownBy(() -> create.execute("usr_buyer", request())).isInstanceOf(BusinessException.class);
        assertThat(read.execute(o.getOrderNo(), "usr_buyer").getTitle()).isEqualTo("한글 게임");
    }
    @Test void newPublicationDoesNotRewriteOrderTerms() {
        var first = saved("usr_buyer");
        when(catalog.detail(anyString())).thenReturn(new PublishedProduct(1L, "Game-1", "New title", 4, 20000, "KRW"));
        assertThatThrownBy(() -> create.execute("usr_buyer", request())).isInstanceOf(BusinessException.class);
        var next = create.execute("usr_buyer", new OrderRequest(id(), "Game-1", 4, 20000));
        assertThat(stored(first).getTitle()).isEqualTo("한글 게임");
        assertThat(stored(first).getAmount()).isEqualTo(10000);
        assertThat(next.getTitle()).isEqualTo("New title");
    }
    @Test void unavailableMalformedMissingAndFreeProductsLeaveNoOrderOrOutbox() {
        for (var error : new BusinessException[]{new BusinessException(ErrorCode.NOT_FOUND),
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
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("relay record failed"); })
                        .when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        try {
            assertThatThrownBy(() -> saved("usr_buyer")).isInstanceOf(IllegalStateException.class);
            assertThat(count("purchase_order")).isZero(); assertThat(count("outbox_event")).isZero();
        } finally { reset(outbox); }
    }
    @Test void sameRequestRaceCreatesExactlyOneOrderAndEvent() throws Exception {
        var input = request(); var barrier = new CyclicBarrier(4);
        when(catalog.detail(anyString())).thenAnswer(inv -> { barrier.await(10, TimeUnit.SECONDS); return product(); });
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i=0;i<4;i++) futures.add(pool.submit(() -> create.execute("usr_buyer", input).getOrderNo()));
            var numbers = new HashSet<String>();
            for (var f : futures) numbers.add(f.get(25, TimeUnit.SECONDS));
            assertThat(numbers).hasSize(1);
            assertThat(count("purchase_order")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentCommitIsRecoveredEvenWhenTheFirstCatalogCallFails() throws Exception {
        var input = request(); var entered = new CountDownLatch(1); var committed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(catalog.detail(anyString())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
            }
            return product();
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var waiting = pool.submit(() -> create.execute("usr_buyer", input));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var winner = create.execute("usr_buyer", input); committed.countDown();
            assertThat(waiting.get(15, TimeUnit.SECONDS).getOrderNo()).isEqualTo(winner.getOrderNo());
            assertThat(count("outbox_event")).isEqualTo(1);
        } finally { committed.countDown(); pool.shutdownNow(); }
    }
    @Test void requestKeysAndBuyerSubjectsUseExactCaseAndSeparateOwners() {
        var request = new OrderRequest("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "Game-1", 3, 10000);
        create.execute("usr_buyer", request); create.execute("USR_BUYER", request);
        create.execute("usr_buyer", new OrderRequest(request.requestId().toUpperCase(Locale.ROOT), "Game-1", 3, 10000));
        assertThat(count("purchase_order")).isEqualTo(3);
    }
    @Test void appliesSuccessAndFailureAndKeepsStableResultOnRedelivery() {
        var a = saved("usr_buyer"); var event = completed(a); accept(event);
        var updated = stored(a).getUpdatedAt(); accept(event);
        assertThat(stored(a).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stored(a).getUpdatedAt()).isEqualTo(updated);
        var b = saved("usr_buyer"); var failure = failed(b); accept(failure); accept(failure);
        assertThat(stored(b).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(stored(b).getFailureReasonCode()).isEqualTo("DECLINED");
        assertThat(count("processed_event")).isEqualTo(2);
    }
    @Test void mismatchedConnectionsAndFeeDeductedAmountsRollBackInboxAndState() {
        var o = saved("usr_buyer"); var good = completed(o);
        for (var bad : new OrderPaymentCompletedEvent[]{
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), "wrong", good.paymentId(), o.getBuyerSubject(), 10000, "KRW"),
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), o.getRequestEventId(), good.paymentId(), "other", 10000, "KRW"),
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), o.getRequestEventId(), good.paymentId(), o.getBuyerSubject(), 9800, "KRW"),
                new OrderPaymentCompletedEvent(id(), good.occurredAt(), o.getOrderNo(), o.getRequestEventId(), good.paymentId(), o.getBuyerSubject(), 10000, "USD")})
            assertThatThrownBy(() -> accept(bad)).isInstanceOf(BusinessException.class);
        assertThat(count("processed_event")).isZero(); assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PENDING);
        accept(good);
    }
    @Test void invalidEnvelopeMissingOrderAndIncompleteFailureAreNotMarkedProcessed() {
        var o = saved("usr_buyer"); var e = completed(o);
        assertThatThrownBy(() -> apply.execute(e.eventType(), PaymentResultMapper.from(new EventEnvelope("wrong", e.eventType(), e.orderNo(), ""), e))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> apply.execute(e.eventType(), PaymentResultMapper.from(new EventEnvelope(e.eventId(), e.eventType(), "wrong", ""), e))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(completed(order()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(new OrderPaymentFailedEvent(id(), e.occurredAt(), e.orderNo(), e.requestEventId(), e.paymentId(), e.buyerSubject(), e.amount(), e.currency(), "", null)))
                .isInstanceOf(BusinessException.class);
        assertThat(count("processed_event")).isZero();
    }
    @Test void rejectsChangedBodyNewFinalEventAndEventIdReuseAcrossOrders() {
        var a = saved("usr_buyer"); var b = saved("usr_buyer"); var e = completed(a); accept(e);
        assertThatThrownBy(() -> accept(new OrderPaymentCompletedEvent(e.eventId(), e.occurredAt(), e.orderNo(), e.requestEventId(), "different-payment", e.buyerSubject(), e.amount(), e.currency())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(completed(a))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(failed(a))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accept(new OrderPaymentCompletedEvent(e.eventId(), e.occurredAt(), b.getOrderNo(), b.getRequestEventId(), id(), b.getBuyerSubject(), b.getAmount(), b.getCurrency())))
                .isInstanceOf(BusinessException.class);
        assertThat(stored(b).getStatus()).isEqualTo(OrderStatus.PENDING); assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void failureAfterInboxInsertRollsBackAndOriginalEventCanBeDeliveredAgain() {
        var o = saved("usr_buyer"); var e = completed(o);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("after inbox"); })
                        .when(guard).firstDelivery(eq(e.eventId()), anyString(), anyString()));
        try {
            assertThatThrownBy(() -> accept(e)).isInstanceOf(IllegalStateException.class);
            assertThat(count("processed_event")).isZero(); assertThat(stored(o).paymentResult()).isNull();
        } finally { reset(guard); }
        accept(e); assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PAID);
    }
    @Test void rollbackAfterFlushingTheFinalStateAlsoRollsBackInbox() {
        var o = saved("usr_buyer"); var e = completed(o);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            accept(e);
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, o.getId())).isEqualTo("PAID");
            assertThat(count("processed_event")).isEqualTo(1);
            throw new IllegalStateException("commit aborted after state flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored(o).paymentResult()).isNull(); assertThat(count("processed_event")).isZero();
        accept(e); assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PAID);
    }
    @Test void simultaneousOppositeResultsAcceptExactlyOneTerminalFact() throws Exception {
        var o = saved("usr_buyer"); var success = completed(o); var failure = failed(o);
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(pool.submit(() -> { start.await(); try { accept(success); return true; } catch (BusinessException ex) { return false; } }),
                    pool.submit(() -> { start.await(); try { accept(failure); return true; } catch (BusinessException ex) { return false; } }));
            start.countDown(); int accepted = 0;
            for (var f : tasks) if (f.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(1);
            assertThat(stored(o).getStatus()).isIn(OrderStatus.PAID, OrderStatus.FAILED);
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentReuseOfOneResultEventIdCannotFinalizeTwoOrders() throws Exception {
        var a = saved("usr_buyer"); var b = saved("usr_buyer"); var e = completed(a);
        var other = new OrderPaymentCompletedEvent(e.eventId(), e.occurredAt(), b.getOrderNo(), b.getRequestEventId(), id(), b.getBuyerSubject(), b.getAmount(), b.getCurrency());
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(pool.submit(() -> { start.await(); try { accept(e); return true; } catch (RuntimeException ex) { return false; } }),
                    pool.submit(() -> { start.await(); try { accept(other); return true; } catch (RuntimeException ex) { return false; } }));
            start.countDown(); int accepted = 0;
            for (var f : tasks) if (f.get(25, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(1);
            var loser = stored(a).getStatus() == OrderStatus.PENDING ? e : other;
            assertThatThrownBy(() -> accept(loser)).isInstanceOf(BusinessException.class);
            assertThat(List.of(stored(a), stored(b))).filteredOn(o -> o.getStatus() == OrderStatus.PAID).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void onlyOwnedOrdersAreReturnedWithStablePagesAndNoInternalFields() {
        var own = saved("usr_buyer"); saved("usr_other"); var last = saved("usr_buyer");
        jdbc.update("UPDATE purchase_order SET created_at='2026-10-16 00:00:00'");
        assertThat(search.execute("usr_buyer", 0, 1).getContent().getFirst().getOrderNo()).isEqualTo(last.getOrderNo());
        assertThat(search.execute("usr_buyer", 1, 1).getTotalElements()).isEqualTo(2);
        String bearer = token("usr_buyer", "MEMBER");
        var detail = http("GET", "/api/v1/orders/" + own.getOrderNo(), null, bearer);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().toString()).doesNotContain("buyerSubject", "requestEventId", "paymentResultEventId");
        assertThat(http("GET", "/api/v1/orders/" + own.getOrderNo(), null, token("usr_other", "MEMBER")).status()).isEqualTo(404);
        assertThat(http("GET", "/api/v1/orders/" + own.getOrderNo().toUpperCase(Locale.ROOT), null, bearer).status()).isEqualTo(404);
        for (String query : List.of("page=-1", "size=0", "size=101"))
            assertThat(http("GET", "/api/v1/orders?" + query, null, bearer).status()).isEqualTo(400);
    }
    @Test void authenticatesJwtAndIgnoresForgedOwnerAndStatus() {
        String bearer = token("usr_buyer", "MEMBER"); var input = request();
        var forged = new HashMap<String,Object>(body(input)); forged.put("buyerSubject", "usr_other"); forged.put("memberId", 999); forged.put("status", "PAID");
        var created = http("POST", "/api/v1/orders", forged, bearer);
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
        for (var field : List.of("requestId", "productCode", "expectedContentVersion", "expectedAmount")) {
            var invalid = new HashMap<String,Object>(body(request())); invalid.remove(field);
            assertThat(http("POST", "/api/v1/orders", invalid, bearer).status()).isEqualTo(400);
        }
        for (String field : List.of("expectedContentVersion", "expectedAmount")) {
            var invalid = new HashMap<String,Object>(body(request())); invalid.put(field, 1.5);
            assertThat(http("POST", "/api/v1/orders", invalid, bearer).status()).isEqualTo(400);
        }
        var invalid = new HashMap<String,Object>(body(request())); invalid.put("expectedAmount", -1);
        assertThat(http("POST", "/api/v1/orders", invalid, bearer).status()).isEqualTo(400);
        assertThat(count("purchase_order")).isZero();
    }
    @Test void kafkaRetriesInvalidResultToDltAndReplaysOriginalAfterOrderRecovery() throws Exception {
        var o = saved("usr_buyer"); var e = completed(o);
        // A missing request is recoverable by restoring its original Order row.
        jdbc.update("UPDATE purchase_order SET order_no=? WHERE id=?", id(), o.getId());
        var config = new HashMap<String,Object>();
        config.put("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id", "order-dlt-" + id()); config.put("auto.offset.reset", "earliest");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (var consumer = new KafkaConsumer<String,String>(config)) {
            consumer.subscribe(List.of(Topics.PAYMENT + ".DLT")); listeners.start();
            send(e);
            boolean found = false; long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (!found && System.nanoTime() < deadline)
                for (var record : consumer.poll(Duration.ofMillis(500))) if (record.value().contains(e.eventId())) found = true;
            assertThat(found).isTrue(); assertThat(count("processed_event")).isZero();
            jdbc.update("UPDATE purchase_order SET order_no=? WHERE id=?", o.getOrderNo(), o.getId());
            send(e); send(e);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(stored(o).getStatus()).isEqualTo(OrderStatus.PAID));
            assertThat(count("processed_event")).isEqualTo(1);
        } finally { listeners.stop(); }
    }
    private void send(DomainEvent e) throws Exception {
        var record = new ProducerRecord<String,String>(e.topic(), e.partitionKey(), mapper.writeValueAsString(e));
        record.headers().add(EventHeaders.EVENT_ID, e.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, e.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }
    @Test void openApiMatchesSnapshotAndContainsOnlyThreeBusinessOperations() throws Exception {
        OpenApiSnapshot.verify(port, "order");
        var paths = http("GET", "/v3/api-docs", null, null).body().path("paths");
        assertThat(paths.size()).isEqualTo(2);
        assertThat(paths.path("/api/v1/orders").has("post")).isTrue();
        assertThat(paths.path("/api/v1/orders").has("get")).isTrue();
    }
}
