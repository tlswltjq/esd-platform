package com.stove.payment;


import static com.stove.payment.PaymentFixtures.approved;
import static com.stove.payment.PaymentFixtures.event;
import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.adapter.pg.SimulatedPgGateway;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentOrderRequest;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class PaymentIntegrationTest extends PaymentIntegrationTestBase {
    @Autowired ReceiveOrderPayment receive;
    @Autowired ResolvePayment resolve;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired OutboxEventRepository events;
    @MockitoSpyBean SimulatedPgGateway gateway;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoSpyBean ProcessedEventGuard guard;
    @LocalServerPort int port;

    @BeforeEach void clean() {
        listeners.stop();
        jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM simulated_payment_charge");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM processed_event");
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private Payment stored(Payment p) { return payments.findById(p.getId()).orElseThrow(); }
    private void run(Payment p) { resolve.execute(p.getPaymentId()); }
    private void tx(Runnable action) { new TransactionTemplate(transactions).executeWithoutResult(s -> action.run()); }

    @Test void receiptIsDurableWithoutCallingPgAndDuplicateKeepsIdentity() {
        PaymentOrderRequest input=request(); Payment first=receive.execute(input); Payment again=receive.execute(input);
        assertThat(again.getPaymentId()).isEqualTo(first.getPaymentId());
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(first.orderRequest()).isEqualTo(input);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("outbox_event")).isZero(); verifyNoInteractions(gateway);
    }
    @Test void changedBodyAndEventIdReuseAreCheckedBeforeInboxDeduplication() {
        PaymentOrderRequest r=request(); receive.execute(r);
        for (PaymentOrderRequest changed : List.of(
                new PaymentOrderRequest(r.eventId(),r.occurredAt(),r.orderNo(),r.buyerSubject(),r.productId(),r.productCode(),"Changed",r.contentVersion(),r.listAmount(),0,r.amount(),r.currency()),
                new PaymentOrderRequest(id(),r.occurredAt(),r.orderNo(),r.buyerSubject(),r.productId(),r.productCode(),r.title(),r.contentVersion(),r.listAmount(),0,r.amount(),r.currency()),
                new PaymentOrderRequest(r.eventId(),r.occurredAt().plusSeconds(1),r.orderNo(),r.buyerSubject(),r.productId(),r.productCode(),r.title(),r.contentVersion(),r.listAmount(),0,r.amount(),r.currency()),
                new PaymentOrderRequest(r.eventId(),r.occurredAt(),id(),r.buyerSubject(),r.productId(),r.productCode(),r.title(),r.contentVersion(),r.listAmount(),0,r.amount(),r.currency())))
            assertThatThrownBy(() -> receive.execute(changed)).isInstanceOf(BusinessException.class);
        assertThat(count("purchase_payment")).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void inboxAndReceiptRollBackTogetherAndInconsistentMarkersAreRejected() {
        PaymentOrderRequest r=request();
        tx(() -> doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("after inbox"); })
                .when(guard).firstDelivery(eq(r.eventId()),anyString(),anyString()));
        assertThatThrownBy(() -> receive.execute(r)).isInstanceOf(IllegalStateException.class);
        assertThat(count("processed_event")).isZero(); assertThat(count("purchase_payment")).isZero();
        reset(guard); receive.execute(r); jdbc.update("DELETE FROM processed_event");
        assertThatThrownBy(() -> receive.execute(r)).isInstanceOf(BusinessException.class);
        jdbc.update("DELETE FROM purchase_payment");
        tx(() -> guard.firstDelivery(r.eventId(),ReceiveOrderPayment.CONSUMER_GROUP,EventType.ORDER_PLACED));
        assertThatThrownBy(() -> receive.execute(r)).isInstanceOf(BusinessException.class);
        assertThat(count("purchase_payment")).isZero();
    }
    @Test void concurrentReceiptsConvergeOnRetryInFreshTransactions() throws Exception {
        PaymentOrderRequest r=request(); CountDownLatch start=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            ArrayList<Future<?>> tasks=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) tasks.add(pool.submit(() -> {
                start.await();
                try { receive.execute(r); } catch(RuntimeException race) { receive.execute(r); }
                return null;
            }));
            start.countDown(); for(Future<?> f:tasks) f.get(20,TimeUnit.SECONDS);
            assertThat(count("purchase_payment")).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void approvalPreservesAmountAndStoresOneStableResultOutsidePgTransaction() throws Exception {
        Payment p=receive.execute(request());
        doAnswer(inv -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return inv.callRealMethod(); }).when(gateway).charge(any());
        run(p); run(p);
        Payment finalPayment=stored(p); assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(count("simulated_payment_charge")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        OutboxEvent event=events.findAll().getFirst(); JsonNode body=mapper.readTree(event.getPayload());
        assertThat(event.getEventId()).isEqualTo(finalPayment.getResultEventId());
        assertThat(body.path("requestEventId").asText()).isEqualTo(p.getRequestEventId());
        assertThat(body.path("amount").asLong()).isEqualTo(p.getAmount());
        assertThat(body.path("paymentId").asText()).isEqualTo(p.getPaymentId());
        assertThat(event.getPartitionKey()).isEqualTo(p.getOrderNo());
        assertThat(event.getEventType()).isEqualTo(EventType.ORDER_PAYMENT_COMPLETED);
        verify(gateway,times(1)).charge(any());
    }
    @Test void supplierDeclineIsTheOnlyFailureAndFirstDecisionSurvivesConfigurationChange() {
        Payment p=receive.execute(request());
        SimulatedPgGateway declined=new SimulatedPgGateway(jdbc,PgResult.Decision.DECLINED,transactions);
        PgResult decision=declined.charge(p.chargeRequest());
        assertThat(gateway.charge(p.chargeRequest())).isEqualTo(decision);
        run(p);
        assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored(p).getFailureReasonCode()).isEqualTo("DECLINED");
        assertThat(events.findAll().getFirst().getEventType()).isEqualTo(EventType.ORDER_PAYMENT_FAILED);
        assertThat(count("simulated_payment_charge")).isEqualTo(1);
    }
    @Test void lostResponseAfterSupplierCommitIsRecoveredWithTheOriginalKey() {
        Payment p=receive.execute(request());
        doAnswer(inv -> { inv.callRealMethod(); throw new PgUnavailable("response lost after commit"); }).when(gateway).charge(any());
        run(p);
        assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored(p).getLastErrorCode()).isEqualTo("PG_UNAVAILABLE");
        assertThat(count("simulated_payment_charge")).isEqualTo(1); assertThat(count("outbox_event")).isZero();
        reset(gateway); run(p);
        assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stored(p).getPaymentId()).isEqualTo(p.getPaymentId());
        assertThat(count("simulated_payment_charge")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void resultAndOutboxRollbackDoesNotRollbackSupplierJournal() {
        Payment p=receive.execute(request());
        tx(() -> doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("after outbox insert"); })
                .when(outbox).record(anyString(),anyString(),any(DomainEvent.class)));
        run(p);
        assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored(p).getResultEventId()).isNull();
        assertThat(stored(p).getLastErrorCode()).isEqualTo("RESULT_UNCONFIRMED");
        assertThat(count("simulated_payment_charge")).isEqualTo(1); assertThat(count("outbox_event")).isZero();
        reset(outbox); run(p);
        assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(count("simulated_payment_charge")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void invalidSupplierIdentityAmountProviderAndMissingResponseRemainPending() {
        Payment p=receive.execute(request()); PgResult r=approved(p);
        for (PgResult bad:List.of(
                new PgResult("OTHER",r.paymentId(),r.orderNo(),r.amount(),r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null),
                new PgResult(r.provider(),id(),r.orderNo(),r.amount(),r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null),
                new PgResult(r.provider(),r.paymentId(),id(),r.amount(),r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null),
                new PgResult(r.provider(),r.paymentId(),r.orderNo(),9800,r.currency(),r.transactionId(),r.occurredAt(),r.decision(),null,null))) {
            doReturn(bad).when(gateway).charge(any()); run(p);
            assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PENDING);
        }
        doReturn(null).when(gateway).charge(any()); run(p);
        assertThat(stored(p).getLastErrorCode()).isEqualTo("PG_INVALID_RESULT");
        assertThat(count("outbox_event")).isZero(); assertThat(count("simulated_payment_charge")).isZero();
    }
    @Test void concurrentResolversUseOneSupplierDecisionAndOneResultEvent() throws Exception {
        Payment p=receive.execute(request()); CyclicBarrier barrier=new CyclicBarrier(2);
        doAnswer(inv -> { barrier.await(10,TimeUnit.SECONDS); return inv.callRealMethod(); }).when(gateway).charge(any());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> a=pool.submit(() -> run(p)); Future<?> b=pool.submit(() -> run(p));
            a.get(20,TimeUnit.SECONDS); b.get(20,TimeUnit.SECONDS);
            assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(count("simulated_payment_charge")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void duplicateSupplierKeyCannotBeReusedForDifferentOrderOrAmount() {
        Payment p=receive.execute(request()); PgResult original=gateway.charge(p.chargeRequest());
        assertThatThrownBy(() -> gateway.charge(new PgChargeRequest(p.getPaymentId(),id(),p.getAmount(),"KRW"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.charge(new PgChargeRequest(p.getPaymentId(),p.getOrderNo(),1,"KRW"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(gateway.charge(p.chargeRequest())).isEqualTo(original); assertThat(count("simulated_payment_charge")).isEqualTo(1);
    }
    @Test void transactionIdReuseCannotFinalizeAnotherPayment() {
        Payment a=receive.execute(request()); Payment b=receive.execute(request()); run(a); PgResult first=stored(a).supplierResult();
        PgResult collision=new PgResult(first.provider(),b.getPaymentId(),b.getOrderNo(),b.getAmount(),b.getCurrency(),first.transactionId(),first.occurredAt(),first.decision(),null,null);
        doReturn(collision).when(gateway).charge(any()); run(b);
        assertThat(stored(b).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void workerRespectsDueTimeAndNeverCallsPgUnderAnOuterTransaction() {
        Payment pending=receive.execute(request()); Payment due=receive.execute(request());
        tx(() -> payments.lockByPaymentId(pending.getPaymentId()).orElseThrow().defer(Payment.now().plusSeconds(60),"WAIT"));
        resolve.execute();
        assertThat(stored(pending).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored(due).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThatThrownBy(() -> tx(() -> run(pending))).isInstanceOf(IllegalStateException.class);
    }
    @Test void oneUnavailablePaymentDoesNotPreventProcessingTheNextDuePayment() {
        Payment first=receive.execute(request()); Payment next=receive.execute(request());
        doAnswer(inv -> {
            PgChargeRequest input=inv.getArgument(0);
            if(input.paymentId().equals(first.getPaymentId())) throw new PgUnavailable("temporary supplier outage");
            return inv.callRealMethod();
        }).when(gateway).charge(any());
        resolve.execute();
        assertThat(stored(first).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored(first).getNextAttemptAt()).isAfter(Payment.now());
        assertThat(stored(next).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void lateUnavailableResponseCannotReopenAConcurrentlyConfirmedPayment() throws Exception {
        Payment p=receive.execute(request()); CountDownLatch entered=new CountDownLatch(1); CountDownLatch release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        doAnswer(inv -> {
            Object result=inv.callRealMethod();
            if(calls.incrementAndGet()==1) {
                entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();
                throw new PgUnavailable("late response loss");
            }
            return result;
        }).when(gateway).charge(any());
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> late=pool.submit(() -> run(p)); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            run(p); Payment confirmed=stored(p); release.countDown(); late.get(20,TimeUnit.SECONDS);
            assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(stored(p).getResultEventId()).isEqualTo(confirmed.getResultEventId());
            assertThat(stored(p).getUpdatedAt()).isEqualTo(confirmed.getUpdatedAt());
            assertThat(stored(p).getNextAttemptAt()).isNull(); assertThat(stored(p).getLastErrorCode()).isNull();
            assertThat(count("outbox_event")).isEqualTo(1); assertThat(count("simulated_payment_charge")).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void chargesTheDiscountedCustomerAmountWithoutReapplyingPricingPolicy() {
        PaymentOrderRequest r=request();
        Payment p=receive.execute(new PaymentOrderRequest(r.eventId(),r.occurredAt(),r.orderNo(),r.buyerSubject(),r.productId(),r.productCode(),r.title(),r.contentVersion(),10000,1000,9000,"KRW"));
        run(p);
        assertThat(stored(p).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(jdbc.queryForObject("SELECT amount FROM simulated_payment_charge",Long.class)).isEqualTo(9000);
        assertThat(stored(p).getListAmount()).isEqualTo(10000); assertThat(stored(p).getDiscountAmount()).isEqualTo(1000);
    }
    @Test void deadOutboxRequeuePreservesOriginalIdentityPayloadAndPaymentDecision() {
        Payment p=receive.execute(request()); run(p); OutboxEvent original=events.findAll().getFirst();
        tx(() -> { OutboxEvent e=events.findById(original.getId()).orElseThrow(); e.markFailed("broker unavailable",1); });
        assertThat(events.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DEAD);
        tx(() -> events.findById(original.getId()).orElseThrow().requeue());
        OutboxEvent requeued=events.findById(original.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(requeued.getEventId()).isEqualTo(original.getEventId()); assertThat(requeued.getPayload()).isEqualTo(original.getPayload());
        run(p); assertThat(count("outbox_event")).isEqualTo(1); assertThat(count("simulated_payment_charge")).isEqualTo(1);
    }
    record Response(int status,JsonNode body) { JsonNode data() { return body.path("data"); } }
    private Response http(String method,String path,String bearer) {
        RestClient.RequestBodySpec spec=RestClient.create("http://127.0.0.1:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(bearer!=null) spec.header("Authorization","Bearer "+bearer);
        return spec.exchange((r,s) -> new Response(s.getStatusCode().value(),mapper.readTree(s.getBody())));
    }
    @Test void signedJwtRestrictsOwnedReadsAndIgnoresForgedOwnerParameters() {
        Payment own=receive.execute(request()); PaymentOrderRequest r=request();
        Payment other=receive.execute(new PaymentOrderRequest(r.eventId(),r.occurredAt(),r.orderNo(),"usr_other",r.productId(),r.productCode(),r.title(),r.contentVersion(),r.listAmount(),r.discountAmount(),r.amount(),r.currency()));
        Payment last=receive.execute(request()); jdbc.update("UPDATE purchase_payment SET created_at='2026-10-16 00:00:00'");
        String bearer=token("usr_buyer","MEMBER");
        Response page=http("GET","/api/v1/payments?size=1&buyerSubject=usr_other&memberId=999",bearer);
        assertThat(page.status()).isEqualTo(200); assertThat(page.data().path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.data().path("content").get(0).path("orderNo").asText()).isEqualTo(last.getOrderNo());
        Response detail=http("GET","/api/v1/payments/"+own.getOrderNo(),bearer);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().toString()).doesNotContain("buyerSubject","requestEventId","resultEventId","providerTransactionId","nextAttemptAt");
        assertThat(http("GET","/api/v1/payments/"+other.getOrderNo(),bearer).status()).isEqualTo(404);
        assertThat(http("GET","/api/v1/payments/"+own.getOrderNo().toUpperCase(Locale.ROOT),bearer).status()).isEqualTo(404);
        for(String query:List.of("page=-1","size=0","size=101")) assertThat(http("GET","/api/v1/payments?"+query,bearer).status()).isEqualTo(400);
    }
    @Test void invalidJwtAndObsoleteMutationEndpointsAreRejected() {
        String good=token("usr_buyer","MEMBER");
        assertThat(http("GET","/api/v1/payments",null).status()).isEqualTo(401);
        assertThat(http("GET","/api/v1/payments",token("creator","CREATOR")).status()).isEqualTo(403);
        for(String invalid:List.of(token("usr_buyer","MEMBER","https://wrong","esd-api",Instant.now().plusSeconds(300)),
                token("usr_buyer","MEMBER",ISSUER,"wrong",Instant.now().plusSeconds(300)),
                token("usr_buyer","MEMBER",ISSUER,"esd-api",Instant.now().minusSeconds(300)),good.substring(0,good.length()-8)+"badbadbad"))
            assertThat(http("GET","/api/v1/payments",invalid).status()).isEqualTo(401);
        for(String path:List.of("/api/v1/payments/prepare","/api/v1/payments/callback","/api/v1/payments/simulator/approve","/api/v1/payments/refund","/api/v1/ops/dlt"))
            assertThat(http("POST",path,good).status()).isEqualTo(403);
    }
    @Test void kafkaInvalidRequestReachesDltWithoutReceiptAndOriginalCanBeReplayed() throws Exception {
        PaymentOrderRequest r=request(); OrderPlacedEvent event=event(r); JsonNode invalid=mapper.valueToTree(event); ((ObjectNode)invalid).put("amount",9800);
        HashMap<String, Object> config=new HashMap<String,Object>();
        config.put("bootstrap.servers",kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        config.put("group.id","payment-dlt-"+id()); config.put("auto.offset.reset","earliest");
        config.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer"); config.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        try(KafkaConsumer<String, String> consumer=new KafkaConsumer<String,String>(config)) {
            consumer.subscribe(List.of(Topics.ORDER+".DLT")); listeners.start(); send(event,mapper.writeValueAsString(invalid));
            boolean found=false; long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
            while(!found && System.nanoTime()<deadline) for(ConsumerRecord<String, String> record:consumer.poll(Duration.ofMillis(500))) if(record.value().contains(r.eventId())) found=true;
            assertThat(found).isTrue(); assertThat(count("purchase_payment")).isZero(); assertThat(count("processed_event")).isZero();
            send(event,mapper.writeValueAsString(event)); send(event,mapper.writeValueAsString(event));
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("purchase_payment")).isEqualTo(1));
            assertThat(count("processed_event")).isEqualTo(1); assertThat(count("simulated_payment_charge")).isZero();
        } finally { listeners.stop(); }
    }
    private void send(DomainEvent event,String payload) throws Exception {
        ProducerRecord<String, String> record=new ProducerRecord<String,String>(event.topic(),event.partitionKey(),payload);
        record.headers().add(EventHeaders.EVENT_ID,event.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE,event.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10,TimeUnit.SECONDS);
    }
    @Test void openApiContainsOnlyTwoReadOperations() throws Exception {
        OpenApiSnapshot.verify(port,"payment");
        JsonNode paths=http("GET","/v3/api-docs",null).body().path("paths");
        assertThat(paths.size()).isEqualTo(2);
        assertThat(paths.path("/api/v1/payments").size()).isEqualTo(1);
        assertThat(paths.path("/api/v1/payments").has("get")).isTrue();
        assertThat(paths.path("/api/v1/payments/{orderNo}").has("get")).isTrue();
    }
}
