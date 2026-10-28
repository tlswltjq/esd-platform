package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRequest;
import com.stove.order.core.domain.RefundReviewRound;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import com.stove.order.core.service.ApplyRefundRestoration;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.order.core.service.DecideRefund;
import com.stove.order.core.service.ApplyRefundPreparation;
import com.stove.order.core.domain.RefundPreparationConfirmation;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.common.kafka.contract.payload.RefundExecutionPreparedEvent;
import com.stove.common.kafka.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.stove.order.core.service.RequestRefund;
import com.stove.order.core.port.CatalogProducts;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class RefundDelayNoticeIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final AtomicLong PRODUCTS = new AtomicLong(1000);
    @MockitoSpyBean OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RequestRefund request;
    @Autowired DecideRefund decide;
    @Autowired ApplyRefundPreparation preparation;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplyLicenseGrant grant;
    @Autowired ApplyRefundSuspension suspension;
    @Autowired ApplyRefundPaymentConfirmation payment;
    @Autowired ApplyRefundRestoration restore;
    @MockitoSpyBean RefundAttemptRepository attempts;
    @MockitoSpyBean RefundReviewRoundRepository rounds;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoBean Clock clock;
    @MockitoBean CatalogProducts catalog;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        listeners.stop(); reset(orders, rounds, outbox, attempts);
        when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC"); jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
    }

    private static final Instant FIRST=BASE.plusSeconds(1800);
    private static final Instant DELAY=FIRST.plusSeconds(10800);

    @Test void onlyTheExactPaymentDeadlineAddsANoticeWhileStatusAndPurchaseRemainUnchanged() {
        Fixture f=executing(); when(clock.instant()).thenReturn(BASE.plusSeconds(86400)); assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
        RefundPreparationConfirmation fact=fact(f,FIRST); preparation.execute(fact);
        for (Instant before:List.of(BASE.plusSeconds(10800),DELAY.minusNanos(1000))) {
            when(clock.instant()).thenReturn(before); assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
        }
        when(clock.instant()).thenReturn(DELAY); Response result=customer(f); assertThat(result.status()).isEqualTo(200); assertThat(result.cacheControl()).contains("no-store");
        assertThat(result.data().path("status").asText()).isEqualTo("PROCESSING"); assertThat(result.data().path("amount").asLong()).isEqualTo(f.order().getAmount());
        JsonNode notice=result.data().path("processingNotice"); assertThat(notice.size()).isEqualTo(3);
        assertThat(notice.path("code").asText()).isEqualTo("RESULT_CONFIRMATION_DELAYED"); assertThat(notice.path("since").asText()).isEqualTo(DELAY.toString());
        assertThat(notice.path("message").asText()).isEqualTo("환불 결과 확인이 지연되고 있습니다. 결과를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.");
        assertThat(orders.findById(f.order().getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(current(f).getReceivedAt()).isEqualTo(BASE); assertThat(current(f).isAutomaticRefundEligible()).isTrue();
        assertThat(result.data().path("retryableAttemptId").isNull()).isTrue();
    }

    @Test void lateDeliveryAndExactReplaysKeepTheOriginalSinceTimeAndReadsNeverMutateState() {
        Fixture f=executing(); RefundPreparationConfirmation fact=fact(f,FIRST); when(clock.instant()).thenReturn(DELAY.plusSeconds(86400)); preparation.execute(fact);
        Response first=customer(f); Map<String,List<Map<String,Object>>> after=snapshot();
        when(clock.instant()).thenReturn(DELAY.plusSeconds(172800)); preparation.execute(fact); assertThat(customer(f).data()).isEqualTo(first.data());
        assertThat(customer(f).data()).isEqualTo(first.data()); assertThat(snapshot()).isEqualTo(after);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='order-refund-preparation'",Integer.class)).isEqualTo(1);
    }

    @Test void moneyCompletionClearsTheNoticeAndOldPreparationCannotReactivateIt() {
        Fixture f=executing(); RefundPreparationConfirmation fact=fact(f,FIRST); preparation.execute(fact); when(clock.instant()).thenReturn(DELAY);
        assertThat(customer(f).data().path("processingNotice").isNull()).isFalse(); confirmMoney(f,"PG_RESPONSE");
        preparation.execute(fact); assertThat(customer(f).data().path("status").asText()).isEqualTo("COMPLETED"); assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
    }

    @Test void preparationMayArriveAfterUnattributedMoneyConfirmationWithoutChangingTheCompletedRefund() {
        Fixture f=executing(); RefundPreparationConfirmation fact=fact(f,FIRST); confirmMoney(f,"PG_QUERY_UNATTRIBUTED");
        preparation.execute(fact); when(clock.instant()).thenReturn(DELAY.plusSeconds(1));
        assertThat(customer(f).data().path("processingNotice").isNull()).isTrue(); assertThat(customer(f).data().path("status").asText()).isEqualTo("COMPLETED");
        RefundAttempt audit=attempt(f); assertThat(audit.isReconciliationRequired()).isTrue(); assertThat(audit.getStatus().name()).isEqualTo("PENDING");
        assertThat(audit.getFirstRefundPreparedAt()).isEqualTo(FIRST); assertThat(current(f).getPaymentConfirmationSource()).isEqualTo("PG_QUERY_UNATTRIBUTED");
    }

    @Test void actualFailureRestorationAndANewAttemptSuppressOldPreparationAndUseTheNewTimer() {
        Fixture f=executing(); RefundAttempt old=attempt(f); RefundPreparationConfirmation oldFact=fact(f,FIRST);
        when(clock.instant()).thenReturn(FIRST.plusSeconds(60)); String failed=id();
        restore.execute(new RefundFailureConfirmation(failed,FIRST.plusSeconds(60),f.refund().getRefundId(),old.getAttemptId(),old.getExecutionRequestEventId(),
                f.order().getOrderNo(),f.order().getPaymentId(),f.order().getBuyerSubject(),f.order().getAmount(),"KRW","pg-original","pg-failed","a".repeat(64)));
        when(clock.instant()).thenReturn(DELAY); preparation.execute(oldFact);
        assertThat(customer(f).data().path("status").asText()).isEqualTo("PROCESSING"); assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
        RefundAttempt failedAttempt=attempt(f); restore.execute(new LicenseRestorationConfirmation(id(),DELAY.plusSeconds(90),failedAttempt.getRestorationRequestEventId(),
                f.refund().getRefundId(),old.getAttemptId(),failed,old.getLicenseId(),f.order().getOrderNo(),f.order().getBuyerSubject(),f.order().getProductId(),f.order().getProductCode(),f.order().getPaymentId(),old.getSuspensionRequestEventId(),old.getSuspensionEventId()));
        assertThat(customer(f).data().path("status").asText()).isEqualTo("FAILED"); assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
        when(clock.instant()).thenReturn(DELAY.plusSeconds(100));
        assertThat(http("POST",path(f)+"/retries",Map.of("previousAttemptId",old.getAttemptId()),"usr_buyer","MEMBER").status()).isEqualTo(202);
        preparation.execute(oldFact); assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
        when(clock.instant()).thenReturn(DELAY.plusSeconds(101)); Refund retry=current(f);
        suspension.execute(new LicenseSuspensionConfirmation(id(),DELAY.plusSeconds(101),retry.getSuspensionRequestEventId(),f.refund().getRefundId(),old.getLicenseId(),
                f.order().getOrderNo(),f.order().getBuyerSubject(),f.order().getProductId(),f.order().getProductCode(),f.order().getPaymentId()));
        RefundPreparationConfirmation next=fact(f,DELAY.plusSeconds(300)); when(clock.instant()).thenReturn(DELAY.plusSeconds(300)); preparation.execute(next); preparation.execute(oldFact);
        assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
        when(clock.instant()).thenReturn(next.confirmationDelayAt()); assertThat(customer(f).data().path("processingNotice").path("since").asText()).isEqualTo(next.confirmationDelayAt().toString());
        assertThat(current(f).getReceivedAt()).isEqualTo(BASE); assertThat(attempts.count()).isEqualTo(2);
    }

    @Test void changedPurchaseRequestSuspensionOrTimerEvidenceCannotWriteAnInboxOrProjection() {
        Fixture f=executing(); RefundPreparationConfirmation good=fact(f,FIRST);
        for (String field:List.of("refundId","requestEventId","paymentId","buyerSubject","licenseSuspensionEventId","previousAttemptId","amount","firstPreparedAt")) {
            RefundPreparationConfirmation bad=new RefundPreparationConfirmation(good.eventId(),field.equals("firstPreparedAt") ? BASE : good.occurredAt(),
                    field.equals("refundId") ? id():good.refundId(),good.attemptId(),field.equals("requestEventId") ? id():good.requestEventId(),good.orderNo(),
                    field.equals("paymentId") ? id():good.paymentId(),field.equals("buyerSubject") ? "other":good.buyerSubject(),field.equals("amount") ? 1:good.amount(),good.currency(),
                    field.equals("licenseSuspensionEventId") ? id():good.licenseSuspensionEventId(),field.equals("previousAttemptId") ? id():null,
                    field.equals("firstPreparedAt") ? BASE:good.firstPreparedAt(),field.equals("firstPreparedAt") ? BASE.plusSeconds(10800):good.confirmationDelayAt());
            Map<String,List<Map<String,Object>>> before=snapshot(); assertThatThrownBy(()->preparation.execute(bad)).isInstanceOf(RefundCompletionRejected.class); assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test void preparationAndInboxRollbackAfterActualFlushAndRedeliveryCanResume() {
        Fixture f=executing(); RefundPreparationConfirmation fact=fact(f,FIRST); Map<String,List<Map<String,Object>>> before=snapshot();
        new TransactionTemplate(transactions).executeWithoutResult(tx->doAnswer(call->{ entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_attempt WHERE preparation_event_id IS NOT NULL",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='order-refund-preparation'",Integer.class)).isEqualTo(1);
            throw new IllegalStateException("after preparation and inbox flush"); }).when(attempts).flush());
        try { assertThatThrownBy(()->preparation.execute(fact)).isInstanceOf(IllegalStateException.class); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(attempts); }
        preparation.execute(fact); assertThat(attempt(f).getPreparationEventId()).isEqualTo(fact.eventId());
    }

    @Test void concurrentRedeliveryStoresOnePreparationAndOneInbox() throws Exception {
        Fixture f=executing(); RefundPreparationConfirmation fact=fact(f,FIRST);
        race(()->{preparation.execute(fact);return new Response(200,null,null,mapper.createObjectNode());},()->{preparation.execute(fact);return new Response(200,null,null,mapper.createObjectNode());});
        assertThat(attempt(f).getPreparationEventId()).isEqualTo(fact.eventId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='order-refund-preparation'",Integer.class)).isEqualTo(1);
    }

    @Test void aChangedReplayOrAnEventIdUsedForAnotherAttemptCannotRewriteEvidence() {
        Fixture f=executing(); RefundPreparationConfirmation fact=fact(f,FIRST); preparation.execute(fact); Fixture other=executing(); RefundPreparationConfirmation unrelated=fact(other,FIRST);
        RefundPreparationConfirmation collision=new RefundPreparationConfirmation(fact.eventId(),unrelated.occurredAt(),unrelated.refundId(),unrelated.attemptId(),unrelated.requestEventId(),unrelated.orderNo(),unrelated.paymentId(),unrelated.buyerSubject(),unrelated.amount(),unrelated.currency(),unrelated.licenseSuspensionEventId(),null,unrelated.firstPreparedAt(),unrelated.confirmationDelayAt());
        Map<String,List<Map<String,Object>>> before=snapshot(); assertThatThrownBy(()->preparation.execute(collision)).isInstanceOf(RefundCompletionRejected.class);
        RefundPreparationConfirmation shifted=new RefundPreparationConfirmation(fact.eventId(),FIRST.plusSeconds(1),fact.refundId(),fact.attemptId(),fact.requestEventId(),fact.orderNo(),fact.paymentId(),fact.buyerSubject(),fact.amount(),fact.currency(),fact.licenseSuspensionEventId(),null,FIRST.plusSeconds(1),DELAY.plusSeconds(1));
        assertThatThrownBy(()->preparation.execute(shifted)).isInstanceOf(RefundCompletionRejected.class); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void anOwnedDelayNoticeCannotBeReadWithAnotherBuyerOrWrongJwt() {
        Fixture f=executing(); preparation.execute(fact(f,FIRST)); when(clock.instant()).thenReturn(DELAY);
        assertThat(http("GET",path(f),null,"other","MEMBER").status()).isEqualTo(404);
        assertThat(http("GET",path(f),null,"usr_buyer",null).status()).isEqualTo(401);
        for (String role:List.of("ADMIN","CREATOR","REVIEWER")) assertThat(http("GET",path(f),null,"usr_buyer",role).status()).isEqualTo(403);
        assertThat(send("GET",path(f),null,token("usr_buyer","MEMBER",ISSUER,"other-api",Instant.now().plusSeconds(300))).status()).isEqualTo(401);
        assertThat(send("GET",path(f),null,token("usr_buyer","MEMBER",ISSUER,"esd-api",Instant.now().minusSeconds(120))).status()).isEqualTo(401);
        assertThat(customer(f).data().path("processingNotice").toString()).doesNotContain("paymentId","attemptId","PG_","firstPreparedAt","secret","requestEventId");
    }

    @Test void realKafkaPreparationAndDuplicateMakeTheNoticeAvailableFromOrderStorage() throws Exception {
        Fixture f=executing(); RefundPreparationConfirmation fact=fact(f,FIRST); when(clock.instant()).thenReturn(DELAY);
        RefundExecutionPreparedEvent event=event(fact);
        for (int index=0;index<2;index++) {
            ProducerRecord<String,String> record=new ProducerRecord<>(event.topic(),event.partitionKey(),mapper.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID,event.eventId().getBytes(StandardCharsets.UTF_8));record.headers().add(EventHeaders.EVENT_TYPE,event.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10,TimeUnit.SECONDS);
        }
        listeners.start();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
            assertThat(customer(f).data().path("processingNotice").path("since").asText()).isEqualTo(DELAY.toString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='order-refund-preparation'",Integer.class)).isEqualTo(1);
        });
    }

    private Fixture executing() {
        when(clock.instant()).thenReturn(BASE); Fixture pending=pending(); grant.execute(grantInput(pending)); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        Refund current=current(pending); String license=jdbc.queryForObject("SELECT license_id FROM order_license_grant WHERE order_no=?",String.class,pending.order().getOrderNo());
        suspension.execute(new LicenseSuspensionConfirmation(id(),BASE.plusSeconds(1),current.getSuspensionRequestEventId(),current.getRefundId(),license,
                pending.order().getOrderNo(),pending.order().getBuyerSubject(),pending.order().getProductId(),pending.order().getProductCode(),pending.order().getPaymentId()));
        return new Fixture(pending.order(),current(pending));
    }
    private RefundAttempt attempt(Fixture f) { return attempts.findByAttemptId(current(f).getCurrentAttemptId()).orElseThrow(); }
    private RefundPreparationConfirmation fact(Fixture f,Instant first) {
        RefundAttempt a=attempt(f); return new RefundPreparationConfirmation(id(),first,f.refund().getRefundId(),a.getAttemptId(),a.getExecutionRequestEventId(),
                f.order().getOrderNo(),f.order().getPaymentId(),f.order().getBuyerSubject(),f.order().getAmount(),f.order().getCurrency(),a.getSuspensionEventId(),a.getPreviousAttemptId(),first,first.plusSeconds(10800));
    }
    private RefundExecutionPreparedEvent event(RefundPreparationConfirmation f) { return new RefundExecutionPreparedEvent(f.eventId(),f.occurredAt(),f.refundId(),f.attemptId(),f.requestEventId(),f.orderNo(),f.paymentId(),f.buyerSubject(),f.amount(),f.currency(),f.licenseSuspensionEventId(),f.previousAttemptId(),f.firstPreparedAt(),f.confirmationDelayAt()); }
    private void confirmMoney(Fixture f,String source) {
        RefundAttempt a=attempt(f); payment.execute(new RefundPaymentConfirmation(id(),FIRST.plusSeconds(2),f.refund().getRefundId(),a.getAttemptId(),a.getExecutionRequestEventId(),
                f.order().getOrderNo(),f.order().getPaymentId(),f.order().getBuyerSubject(),f.order().getAmount(),"KRW",source,"pg-original",List.of("pg-cancel")));
    }
    private Order paid() {
        String code = "Game-" + id();
        Order order = Order.create("usr_buyer", new OrderRequest(id(), code, 3, 10000),
                new PublishedProduct(PRODUCTS.incrementAndGet(), code, "지연 안내 검증 상품", 3, 10000, "KRW"), id());
        ReflectionTestUtils.setField(order, "createdAt", BASE.minusSeconds(21600));
        order.apply(new PaymentResult(id(), BASE.minusSeconds(7200), order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return orders.saveAndFlush(order);
    }
    private Fixture pending() {
        Order order = paid(); Refund refund = request.execute("usr_buyer", order.getOrderNo());
        return new Fixture(order, refund);
    }
    private Fixture rejected() {
        Fixture pending = pending(); Refund refund = pending.refund(); Order order = pending.order();
        decide.execute(new RefundReviewRequest(id(), refund.getRefundId(), "usr_admin", RefundReviewDecision.REJECTED, "최초 반려 사유"));
        return new Fixture(order, refunds.findByOrderNo(order.getOrderNo()).orElseThrow());
    }
    private Response reconsider(Fixture fixture, String requestId, String previous, String reason) {
        return http("POST", path(fixture) + "/reconsiderations", application(requestId, previous, reason), "usr_buyer", "MEMBER");
    }
    private Map<String, String> application(String requestId, String previous, String reason) { return Map.of("requestId", requestId, "previousReviewId", previous, "reason", reason); }
    private Refund current(Fixture fixture) { return refunds.findByOrderNo(fixture.order().getOrderNo()).orElseThrow(); }
    private Response customer(Fixture fixture) { return http("GET", path(fixture), null, "usr_buyer", "MEMBER"); }
    private String adminPath(Fixture fixture) { return "/api/v1/admin/refunds/" + fixture.refund().getRefundId(); }
    private Response verdict(Fixture fixture, String review, String command, String decision, String reason, String actor) {
        return http("POST", adminPath(fixture) + "/reconsiderations/" + review + "/decision", Map.of("requestId", command, "decision", decision, "reason", reason), actor, "ADMIN");
    }
    private Response initialVerdict(Fixture fixture, String command, String decision, String reason, String actor) {
        return http("POST", adminPath(fixture) + "/decision", Map.of("requestId", command, "decision", decision, "reason", reason), actor, "ADMIN");
    }
    private LicenseGrant grantInput(Fixture fixture) {
        return new LicenseGrant(id(), BASE, id(), fixture.order().getOrderNo(), "usr_buyer", fixture.order().getProductId(), fixture.order().getProductCode(), fixture.order().getPaymentId());
    }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("purchase_order", "order_refund", "order_refund_review_round", "order_license_grant", "order_refund_attempt", "outbox_event", "processed_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    private List<Response> race(Callable<Response> first, Callable<Response> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Response> one = pool.submit(() -> { start.await(); return first.call(); }); Future<Response> two = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown(); return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private String path(Fixture fixture) { return "/api/v1/orders/" + fixture.order().getOrderNo() + "/refund"; }
    private Response http(String method, String path, Object body, String subject, String role) {
        return send(method, path, body, role == null ? null : token(subject, role));
    }
    private Response send(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), reply.getHeaders().getFirst("Location"),
                reply.getHeaders().getFirst("Cache-Control"), mapper.readTree(reply.getBody())));
    }
    private record Fixture(Order order, Refund refund) { }
    private record Response(int status, String location, String cacheControl, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
