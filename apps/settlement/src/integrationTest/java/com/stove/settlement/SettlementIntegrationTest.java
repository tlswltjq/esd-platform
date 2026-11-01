package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.id;
import static com.stove.settlement.SettlementFixtures.order;
import static com.stove.settlement.SettlementFixtures.payment;
import static com.stove.settlement.SettlementFixtures.report;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.api.listener.SettlementOrderMapper;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.service.SynchronizePgTransaction;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgReceiptView;
import com.stove.settlement.core.domain.PgSettlement;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.CatalogOwners;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.port.PgSettlements;
import com.stove.settlement.core.service.CloseSellerMonth;
import com.stove.settlement.core.service.ReadPgReceipts;
import com.stove.settlement.core.service.ReceivePgSettlement;
import com.stove.settlement.core.service.ReconcilePgReceipt;
import com.stove.settlement.core.service.RecordPaidSale;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SettlementIntegrationTest extends SettlementIntegrationTestBase {
    @Autowired RecordPaidSale record;
    @Autowired SynchronizePgTransaction synchronize;
    @Autowired CloseSellerMonth close;
    @Autowired ReceivePgSettlement receive;
    @Autowired ReconcilePgReceipt reconcile;
    @Autowired ReadPgReceipts receipts;
    @Autowired SettlementSaleRepository sales;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoBean PgPayments pgPayments;
    @MockitoBean PgSettlements pgSettlements;
    @MockitoBean CatalogOwners owners;
    @MockitoBean Clock clock;
    @MockitoSpyBean ProcessedEventGuard guard;
    @LocalServerPort int port;
    private final Map<String,PgPayment> provider=new ConcurrentHashMap<String,PgPayment>();

    @BeforeEach void clean() {
        listeners.stop();
        for(String table:List.of("pg_receipt_item","pg_receipt","settlement_sale","seller_monthly_settlement","settlement_pg_fee","settlement_pg_transaction","processed_event"))
            jdbc.update("DELETE FROM "+table);
        provider.clear(); when(clock.instant()).thenReturn(NOW);
        when(pgPayments.require(anyString())).thenAnswer(call -> {
            PgPayment payment=provider.get(call.getArgument(0,String.class));
            if(payment==null) throw new IllegalStateException("PG fixture unavailable");
            return payment;
        });
        when(owners.require(anyString())).thenReturn(new ProductOwner(1L,"game-1","usr_seller"));
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    PgPayment prepare(PaidOrder order) { PgPayment payment=payment(order,300); provider.put(order.paymentId(),payment);return payment; }
    SettlementSale sale(PaidOrder order) { prepare(order);return record.execute(order); }
    PgSettlement statement(List<PgPayment> payments) {
        PgSettlement statement=report(payments);when(pgSettlements.require(statement.providerSettlementId())).thenReturn(statement);return statement;
    }

    @Test void savesImmutableSourceAndOneAllocationWithoutChangingCustomerCharge() {
        PaidOrder input=order(); SettlementSale first=sale(input); SettlementSale again=record.execute(input);
        assertThat(again.getId()).isEqualTo(first.getId()); assertThat(again.source()).isEqualTo(input);
        assertThat(first.getAmount()).isEqualTo(10000); assertThat(first.getSellerPayoutAmount()).isEqualTo(9000);
        assertThat(jdbc.queryForObject("SELECT pg_fee_amount FROM settlement_pg_fee",Long.class)).isEqualTo(300); assertThat(count("processed_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT paid_amount FROM seller_monthly_settlement",Long.class)).isEqualTo(10000);
    }
    @Test void fullSourceComparisonHappensBeforeInboxDeduplication() {
        PaidOrder p=order(); sale(p);
        for(int field=0;field<11;field++) {
            PaidOrder changed=new PaidOrder(field==0?id():p.eventId(),field==1?p.occurredAt().plusSeconds(1):p.occurredAt(),p.orderNo(),
                    field==2?id():p.requestEventId(),field==3?id():p.paymentResultEventId(),field==4?id():p.paymentId(),
                    field==5?"other-buyer":p.buyerSubject(),field==6?2L:p.productId(),field==7?"other-code":p.productCode(),
                    field==8?"Changed title":p.title(),field==9?2:p.contentVersion(),field==10?9999:p.amount(),field==11?"USD":p.currency());
            assertThatThrownBy(() -> record.execute(changed)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void eventAndPaymentIdentityReuseCannotCreateAnotherSale() {
        PaidOrder original=order();sale(original);
        for(int field=0;field<4;field++) {
            PaidOrder other=order();
            PaidOrder reused=new PaidOrder(field==0?original.eventId():other.eventId(),other.occurredAt(),other.orderNo(),
                    field==1?original.requestEventId():other.requestEventId(),field==2?original.paymentResultEventId():other.paymentResultEventId(),
                    field==3?original.paymentId():other.paymentId(),other.buyerSubject(),other.productId(),other.productCode(),other.title(),other.contentVersion(),other.amount(),other.currency());
            prepare(reused);assertThatThrownBy(() -> record.execute(reused)).isInstanceOf(BusinessException.class);
        }
        assertThat(count("settlement_sale")).isEqualTo(1);assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void inboxSaleAndMonthlyTotalsRollBackTogether() {
        PaidOrder input=order();prepare(input);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                doAnswer(call -> { call.callRealMethod();throw new IllegalStateException("after inbox fixture"); })
                .when(guard).firstDelivery(anyString(),anyString(),anyString()));
        assertThatThrownBy(() -> record.execute(input)).isInstanceOf(IllegalStateException.class);
        assertThat(count("settlement_sale")).isZero();assertThat(count("seller_monthly_settlement")).isZero();assertThat(count("processed_event")).isZero();
        assertThat(count("settlement_pg_transaction")).isZero();assertThat(count("settlement_pg_fee")).isZero();
    }
    @Test void missingExternalFactsOrInconsistentInboxNeverProduceNormalSales() {
        assertThatThrownBy(() -> record.execute(order())).isInstanceOf(IllegalStateException.class);
        PaidOrder input=order();sale(input);jdbc.update("DELETE FROM processed_event");
        assertThatThrownBy(() -> record.execute(input)).isInstanceOf(BusinessException.class);
        assertThat(count("processed_event")).isZero();
    }
    @Test void concurrentDuplicatesOnlyIncreaseMonthlyAmountsOnce() throws Exception {
        PaidOrder input=order();prepare(input);CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(4)) {
            List<Future<?>> calls=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) calls.add(pool.submit(() -> { start.await();record.execute(input);return null; }));
            start.countDown();for(Future<?> call:calls) call.get(30,TimeUnit.SECONDS);
        }
        assertThat(count("settlement_sale")).isEqualTo(1);assertThat(count("processed_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT record_count FROM seller_monthly_settlement",Long.class)).isEqualTo(1);
    }
    @Test void closingPastMonthKeepsLateSalesInCurrentMonthAndDuplicateKeepsOriginalAllocation() {
        PaidOrder september=order(Instant.parse("2026-09-01T00:00:00Z"));sale(september);
        SellerMonthlySettlement first=close.execute("usr_seller","2026-09");assertThat(first.getPaidAmount()).isEqualTo(10000);
        assertThat(close.execute("usr_seller","2026-09").getClosedAt()).isEqualTo(first.getClosedAt());
        PaidOrder late=order(Instant.parse("2026-09-02T00:00:00Z"));SettlementSale delayed=sale(late);
        assertThat(delayed.getSaleMonth()).isEqualTo("2026-09");assertThat(delayed.getSettlementMonth()).isEqualTo("2026-10");
        when(clock.instant()).thenReturn(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(record.execute(late).getSettlementMonth()).isEqualTo("2026-10");
        assertThat(close.execute("empty-seller","2026-09").getRecordCount()).isZero();
    }
    @Test void currentAndFutureMonthsCannotBeClosed() {
        for(String month:List.of("2026-10","2026-11")) assertThatThrownBy(() -> close.execute("usr_seller",month)).isInstanceOf(BusinessException.class);
        assertThat(count("seller_monthly_settlement")).isZero();
    }
    @Test void unexpectedClosedCurrentBucketIsRejectedWithoutAnUnboundedRetry() {
        sale(order());close.execute("usr_seller","2026-09");
        jdbc.update("UPDATE seller_monthly_settlement SET status='CLOSED',closed_at='2026-10-18' WHERE settlement_month='2026-10'");
        PaidOrder late=order(Instant.parse("2026-09-01T00:00:00Z"));prepare(late);
        assertThatThrownBy(() -> record.execute(late)).isInstanceOf(BusinessException.class);
        assertThat(count("settlement_sale")).isEqualTo(1);assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void concurrentClosingAndSaleChooseAConsistentBucket() throws Exception {
        sale(order(Instant.parse("2026-09-01T00:00:00Z")));
        PaidOrder racing=order(Instant.parse("2026-09-02T00:00:00Z"));prepare(racing);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            Future<?> a=pool.submit(() -> close.execute("usr_seller","2026-09"));Future<?> b=pool.submit(() -> record.execute(racing));
            a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
        }
        SellerMonthlySettlement closed=close.execute("usr_seller","2026-09");
        long actual=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM settlement_sale WHERE settlement_month='2026-09'",Long.class);
        assertThat(closed.getPaidAmount()).isEqualTo(actual);
        assertThat(jdbc.queryForObject("SELECT SUM(paid_amount) FROM seller_monthly_settlement",Long.class)).isEqualTo(20000);
    }
    @Test void reportBeforeSaleWaitsThenReconcilesOnceWithoutExternalCalls() {
        PaidOrder input=order();PgPayment payment=prepare(input);PgSettlement report=statement(List.of(payment));
        PgReceiptView waiting=receive.execute(report.providerSettlementId(),"usr_admin");assertThat(waiting.status()).isEqualTo("REPORTED_PAID");
        assertThat(waiting.simulatedReceivedAt()).isNull();record.execute(input);
        PgReceiptView received=reconcile.execute(report.providerSettlementId());assertThat(received.status()).isEqualTo("SIMULATED_RECEIVED");
        assertThat(received.statement().payoutAmount()).isEqualTo(9700);
        assertThat(receive.execute(report.providerSettlementId(),"another-admin").simulatedReceivedAt()).isEqualTo(received.simulatedReceivedAt());
        assertThat(count("pg_receipt")).isEqualTo(1);assertThat(count("pg_receipt_item")).isEqualTo(1);assertThat(count("settlement_sale")).isEqualTo(1);
    }
    @Test void concurrentReceiptCollectionConvergesOnOneImmutableReportAndReceivedTimestamp() throws Exception {
        PaidOrder input=order();PgPayment payment=prepare(input);record.execute(input);PgSettlement statement=report(List.of(payment));
        CyclicBarrier fetched=new CyclicBarrier(2);
        when(pgSettlements.require(statement.providerSettlementId())).thenAnswer(call -> { fetched.await(10,TimeUnit.SECONDS);return statement; });
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            Future<PgReceiptView> a=pool.submit(() -> receive.execute(statement.providerSettlementId(),"usr_admin_a"));
            Future<PgReceiptView> b=pool.submit(() -> receive.execute(statement.providerSettlementId(),"usr_admin_b"));
            PgReceiptView first=a.get(30,TimeUnit.SECONDS);PgReceiptView second=b.get(30,TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo("SIMULATED_RECEIVED");assertThat(second.simulatedReceivedAt()).isEqualTo(first.simulatedReceivedAt());
            assertThat(first.reportedBy()).isEqualTo(second.reportedBy());
        }
        assertThat(count("pg_receipt")).isEqualTo(1);assertThat(count("pg_receipt_item")).isEqualTo(1);
    }
    @Test void conflictingConcurrentBodiesForSameReportIdCannotOverwriteTheWinningEvidence() throws Exception {
        PgSettlement first=report(List.of(payment(order(),300)));PgSettlement other=report(List.of(payment(order(),300)));
        PgSettlement changed=new PgSettlement(first.provider(),first.providerSettlementId(),other.currency(),other.cutoffAt(),other.paidAt(),
                other.status(),other.paymentAmountTotal(),other.pgFeeAmountTotal(),other.payoutAmount(),other.recordCount(),other.items());
        CyclicBarrier fetched=new CyclicBarrier(2);AtomicInteger calls=new AtomicInteger();
        when(pgSettlements.require(first.providerSettlementId())).thenAnswer(call -> {
            int index=calls.getAndIncrement();fetched.await(10,TimeUnit.SECONDS);return index==0?first:changed;
        });
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            List<Future<String>> outcomes=new ArrayList<Future<String>>();
            for(int i=0;i<2;i++) outcomes.add(pool.submit(() -> {
                try { return receive.execute(first.providerSettlementId(),"usr_admin").status(); }
                catch(BusinessException conflict) { return conflict.errorCode().code(); }
            }));
            List<String> results=new ArrayList<String>();for(Future<String> outcome:outcomes) results.add(outcome.get(30,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("REPORTED_PAID","CONFLICT");
        }
        assertThat(count("pg_receipt")).isEqualTo(1);assertThat(count("pg_receipt_item")).isEqualTo(1);
        assertThat(receipts.execute(first.providerSettlementId()).statement()).isIn(first,changed);
    }
    @Test void inconsistentReportStaysMismatchAndTransactionCannotBeUsedInAnotherReceipt() {
        PaidOrder input=order();PgPayment payment=prepare(input);record.execute(input);
        PgCost original=payment.cost();PgPayment changed=new PgPayment(input.paymentId(),input.orderNo(),10000,"KRW",
                new PgCost("SIMULATED",original.getProviderTransactionId(),original.getProviderOccurredAt(),original.getPgFeePolicyId(),300,500));
        PgSettlement first=statement(List.of(changed));assertThat(receive.execute(first.providerSettlementId(),"usr_admin").status()).isEqualTo("MISMATCH");
        PgSettlement second=statement(List.of(changed));assertThatThrownBy(() -> receive.execute(second.providerSettlementId(),"usr_admin")).isInstanceOf(BusinessException.class);
        assertThat(count("pg_receipt")).isEqualTo(1);assertThat(count("pg_receipt_item")).isEqualTo(1);
    }
    @Test void receivedReceiptDoesNotChangeMonthlyCloseOrNeedProviderToRead() {
        PaidOrder input=order(Instant.parse("2026-09-01T00:00:00Z"));PgPayment payment=prepare(input);record.execute(input);
        SellerMonthlySettlement month=close.execute("usr_seller","2026-09");PgSettlement report=statement(List.of(payment));
        assertThat(receive.execute(report.providerSettlementId(),"usr_admin").status()).isEqualTo("SIMULATED_RECEIVED");
        assertThat(close.execute("usr_seller","2026-09").getClosedAt()).isEqualTo(month.getClosedAt());
        assertThat(receipts.execute(report.providerSettlementId()).statement()).isEqualTo(report);
    }
    record Reply(int status,JsonNode body) { }
    Reply http(String method,String path,String token,Object body) {
        RestClient.RequestBodySpec request=RestClient.create("http://127.0.0.1:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.body(body);
        return request.exchange((r,s) -> new Reply(s.getStatusCode().value(),s.bodyTo(JsonNode.class)));
    }
    @Test void signedRolesAndSubjectScopeProtectSalesMonthsAndReceiptOperations() {
        sale(order());String creator=token("usr_seller","CREATOR");String other=token("usr_other","CREATOR");String admin=token("usr_admin","ADMIN");
        assertThat(http("GET","/api/v1/settlements/me/sales?sellerSubject=usr_other",creator,null).body().path("data").path("totalElements").asLong()).isEqualTo(1);
        Reply owned=http("GET","/api/v1/settlements/me/sales",creator,null);
        assertThat(owned.body().toString()).doesNotContain("buyerSubject","pgCost","platformNetAmount","sellerSubject");
        assertThat(http("GET","/api/v1/settlements/me/sales",other,null).body().path("data").path("totalElements").asLong()).isZero();
        assertThat(http("GET","/api/v1/settlements/sales",admin,null).body().path("data").path("totalElements").asLong()).isEqualTo(1);
        assertThat(http("GET","/api/v1/settlements/sales",creator,null).status()).isEqualTo(403);
        assertThat(http("GET","/api/v1/settlements/pg-receipts",creator,null).status()).isEqualTo(403);
        assertThat(http("GET","/api/v1/settlements/me/sales",token("member","MEMBER"),null).status()).isEqualTo(403);
        assertThat(http("GET","/api/v1/settlements/me/sales",null,null).status()).isEqualTo(401);
        for(String invalidSubject:List.of(token(null,"CREATOR"),token("","CREATOR"),token(" ","CREATOR"),token("x".repeat(101),"CREATOR"))) {
            assertThat(http("GET","/api/v1/settlements/me/sales",invalidSubject,null).status()).isEqualTo(401);
            assertThat(http("GET","/api/v1/settlements/me/months",invalidSubject,null).status()).isEqualTo(401);
        }
        for(String query:List.of("size=0","page=-1","size=101","month=2026-13"))
            assertThat(http("GET","/api/v1/settlements/me/sales?"+query,creator,null).status()).isEqualTo(400);
        assertThat(http("POST","/api/v1/settlements/pg-receipts",admin,Map.of("providerSettlementId",id(),"payoutAmount",1)).status()).isEqualTo(400);
        assertThat(http("POST","/api/v1/settlements/sellers/usr_seller/months/2026-10/close",admin,null).status()).isEqualTo(409);
        for(String invalid:List.of(token("s","CREATOR","wrong","esd-api",Instant.now().plusSeconds(100)),
                token("s","CREATOR",ISSUER,"wrong",Instant.now().plusSeconds(100)),
                token("s","CREATOR",ISSUER,"esd-api",Instant.now().minusSeconds(300)),creator.substring(0,creator.length()-10)+"invalid-signature"))
            assertThat(http("GET","/api/v1/settlements/me/sales",invalid,null).status()).isEqualTo(401);
    }
    @Test void mapperValidatesEnvelopeAndKafkaFailureCanBeReplayedWithOriginalIdentity() throws Exception {
        PaidOrder input=order();prepare(input);
        OrderPaidEvent event=new OrderPaidEvent(input.eventId(),input.occurredAt(),input.orderNo(),input.requestEventId(),input.paymentResultEventId(),
                input.paymentId(),input.buyerSubject(),input.productId(),input.productCode(),input.title(),input.contentVersion(),input.amount(),input.currency());
        ProducerRecord<String,String> invalid=new ProducerRecord<String,String>(Topics.ORDER,input.orderNo(),mapper.writeValueAsString(event));
        invalid.headers().add(EventHeaders.EVENT_ID,id().getBytes(StandardCharsets.UTF_8));invalid.headers().add(EventHeaders.EVENT_TYPE,EventType.ORDER_PAID.getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String,String> malformed=new ConsumerRecord<String,String>(Topics.ORDER,0,0,input.orderNo(),mapper.writeValueAsString(event));
        malformed.headers().add(EventHeaders.EVENT_ID,id().getBytes(StandardCharsets.UTF_8));malformed.headers().add(EventHeaders.EVENT_TYPE,EventType.ORDER_PAID.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> SettlementOrderMapper.from(EventEnvelope.from(malformed),event)).isInstanceOf(BusinessException.class);
        HashMap<String,Object> configuration=new HashMap<String,Object>();configuration.put("bootstrap.servers",kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        configuration.put("group.id","settlement-dlt-"+id());configuration.put("auto.offset.reset","earliest");
        configuration.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");configuration.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        try(KafkaConsumer<String,String> consumer=new KafkaConsumer<String,String>(configuration)) {
            consumer.subscribe(List.of(Topics.ORDER+".DLT"));listeners.start();kafka.send(invalid).get(10,TimeUnit.SECONDS);
            boolean found=false;long deadline=System.nanoTime()+Duration.ofSeconds(35).toNanos();
            while(!found && System.nanoTime()<deadline) for(ConsumerRecord<String,String> failure:consumer.poll(Duration.ofMillis(300))) if(failure.value().contains(input.eventId())) found=true;
            assertThat(found).isTrue();assertThat(count("settlement_sale")).isZero();
            ProducerRecord<String,String> replay=new ProducerRecord<String,String>(Topics.ORDER,input.orderNo(),mapper.writeValueAsString(event));
            replay.headers().add(EventHeaders.EVENT_ID,input.eventId().getBytes(StandardCharsets.UTF_8));replay.headers().add(EventHeaders.EVENT_TYPE,EventType.ORDER_PAID.getBytes(StandardCharsets.UTF_8));
            kafka.send(replay).get(10,TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("settlement_sale")).isEqualTo(1));
        } finally { listeners.stop(); }
    }
    @Test void publicOpenApiHasTenOperationsAndNoLegacyOrInternalRoutes() throws Exception {
        OpenApiSnapshot.verify(port,"settlement");
        JsonNode paths=http("GET","/v3/api-docs",null,null).body().path("paths");
        assertThat(paths.size()).isEqualTo(9);int operations=0;for(JsonNode path:paths) operations+=path.size();assertThat(operations).isEqualTo(10);
        assertThat(paths.toString()).doesNotContain("/internal/","reconciliation","tax-invoice");
    }
    PgPayment withoutCost(PaidOrder input) {
        PgPayment result = new PgPayment(input.paymentId(), input.orderNo(), input.amount(), input.currency(),
                new PgEvidence("SIMULATED", id(), input.occurredAt().minusSeconds(1)), null);
        provider.put(input.paymentId(), result);
        return result;
    }
    PgPayment withFee(PgPayment payment, long fee) {
        PgEvidence evidence = payment.evidence();
        return new PgPayment(payment.paymentId(), payment.orderNo(), payment.amount(), payment.currency(), evidence,
                new PgCost(evidence.provider(), evidence.transactionId(), evidence.occurredAt(), "FEE_CONFIRMED", 300, fee));
    }

    @Test
    void unknownCostCanCloseSellerMonthAndLateCostOnlyChangesThePgView() {
        PaidOrder input = order(Instant.parse("2026-09-01T00:00:00Z"));
        PgPayment pending = withoutCost(input);
        SettlementSale original = record.execute(input);
        String admin = token("usr_admin", "ADMIN");
        JsonNode before = http("GET", "/api/v1/settlements/sales", admin, null).body().path("data").path("content").get(0);
        assertThat(before.path("pgCostStatus").asText()).isEqualTo("UNCONFIRMED");
        assertThat(before.path("pgCost").isNull()).isTrue();
        assertThat(before.path("platformNetAmount").isNull()).isTrue();
        Reply closed = http("POST", "/api/v1/settlements/sellers/usr_seller/months/2026-09/close", admin, null);
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.body().path("data").path("month").path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.body().path("data").path("pgFeeAmount").isNull()).isTrue();
        assertThat(closed.body().path("data").path("pendingPgCostCount").asLong()).isEqualTo(1);
        Instant closedAt = close.execute("usr_seller", "2026-09").getClosedAt();
        Map<String, Object> saleBefore = jdbc.queryForMap("SELECT * FROM settlement_sale");
        Map<String, Object> monthBefore = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        assertThat(count("settlement_pg_transaction")).isEqualTo(1);
        assertThat(count("settlement_pg_fee")).isZero();
        assertThat(jdbc.queryForObject("SELECT legacy_pg_fee_amount IS NULL FROM settlement_sale", Boolean.class)).isTrue();
        String path = "/api/v1/settlements/pg-transactions/" + input.paymentId() + "/refresh";
        assertThat(http("POST", path, token("usr_seller", "CREATOR"), null).status()).isEqualTo(403);
        assertThat(http("POST", path, null, null).status()).isEqualTo(401);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        provider.put(input.paymentId(), withFee(pending, 300));
        Reply refreshed = http("POST", path, admin, null);
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.body().path("data").path("pgCostStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT recorded_by FROM settlement_pg_fee", String.class)).isEqualTo("usr_admin");
        assertThat(http("POST", path, admin, null).body()).isEqualTo(refreshed.body());
        assertThat(count("settlement_pg_fee")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_sale")).isEqualTo(saleBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(monthBefore);
        JsonNode after = http("GET", "/api/v1/settlements/months", admin, null).body().path("data").path("content").get(0);
        assertThat(after.path("pgFeeAmount").asLong()).isEqualTo(300);
        assertThat(after.path("platformNetAmount").asLong()).isEqualTo(700);
        assertThat(after.path("month").path("sellerPayoutAmount").asLong()).isEqualTo(9000);
        assertThat(close.execute("usr_seller", "2026-09").getClosedAt()).isEqualTo(closedAt);
        when(pgPayments.require(input.paymentId())).thenThrow(new IllegalStateException("PG query unavailable"));
        assertThat(record.execute(input).getId()).isEqualTo(original.getId());
        assertThat(count("processed_event")).isEqualTo(1);
    }

    @Test
    void receiptDistinguishesMissingCostAndReconcilesAfterIndependentConfirmation() {
        PaidOrder input = order();
        PgPayment pending = withoutCost(input);
        record.execute(input);
        PgPayment confirmed = withFee(pending, 300);
        PgSettlement statement = statement(List.of(confirmed));
        PgReceiptView waiting = receive.execute(statement.providerSettlementId(), "usr_admin");
        assertThat(waiting.status()).isEqualTo("REPORTED_PAID");
        assertThat(waiting.checks().getFirst().code()).isEqualTo("PG_COST_PENDING");
        provider.put(input.paymentId(), confirmed);
        synchronize.execute(input.paymentId(), "usr_admin");
        when(pgPayments.require(input.paymentId())).thenThrow(new IllegalStateException("PG unavailable after recording"));
        assertThat(reconcile.execute(statement.providerSettlementId()).status()).isEqualTo("SIMULATED_RECEIVED");
        assertThat(count("settlement_sale")).isEqualTo(1);
    }

    @Test
    void concurrentLateCostQueriesConvergeWithoutChangingSellerMonth() throws Exception {
        PaidOrder input = order();
        PgPayment pending = withoutCost(input);
        record.execute(input);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        provider.put(input.paymentId(), withFee(pending, 300));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) calls.add(pool.submit(() -> {
                start.await(); synchronize.execute(input.paymentId(), "usr_admin"); return null;
            }));
            start.countDown();
            for (Future<?> call : calls) call.get(30, TimeUnit.SECONDS);
        }
        assertThat(count("settlement_pg_fee")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(before);
    }

    @Test
    void feeMismatchAndUpstreamFailureCannotOverwriteVerifiedEvidence() {
        PaidOrder input = order();
        PgPayment confirmed = prepare(input);
        record.execute(input);
        Map<String, Object> feeBefore = jdbc.queryForMap("SELECT * FROM settlement_pg_fee");
        Map<String, Object> transactionBefore = jdbc.queryForMap("SELECT * FROM settlement_pg_transaction");
        // 조회 응답에서 비용 자료가 빠져도 이미 확인한 비용은 사라지지 않는다.
        provider.put(input.paymentId(), new PgPayment(confirmed.paymentId(), confirmed.orderNo(), confirmed.amount(),
                confirmed.currency(), confirmed.evidence(), null));
        assertThat(synchronize.execute(input.paymentId(), "usr_admin").payment().cost()).isEqualTo(confirmed.cost());
        provider.put(input.paymentId(), new PgPayment(confirmed.paymentId(), confirmed.orderNo(), confirmed.amount(),
                confirmed.currency(), new PgEvidence("SIMULATED", "wrong-transaction", confirmed.evidence().occurredAt()), null));
        assertThatThrownBy(() -> synchronize.execute(input.paymentId(), "usr_admin")).isInstanceOf(BusinessException.class);
        provider.put(input.paymentId(), withFee(confirmed, 500));
        assertThatThrownBy(() -> synchronize.execute(input.paymentId(), "usr_admin")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_fee")).isEqualTo(feeBefore);
        when(pgPayments.require(input.paymentId())).thenThrow(new IllegalStateException("PG fixture unavailable"));
        assertThatThrownBy(() -> synchronize.execute(input.paymentId(), "usr_admin")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_fee")).isEqualTo(feeBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_transaction")).isEqualTo(transactionBefore);
    }

    @Test
    void aConfirmedZeroCostAndAnUnconfirmedCostRemainDifferentInMonthlyQueries() {
        PaidOrder pendingOrder = order();
        PgPayment pending = withoutCost(pendingOrder);
        record.execute(pendingOrder);
        PaidOrder zeroOrder = order();
        PgPayment zero = withoutCost(zeroOrder);
        provider.put(zeroOrder.paymentId(), withFee(zero, 0));
        record.execute(zeroOrder);
        JsonNode month = http("GET", "/api/v1/settlements/months", token("usr_admin", "ADMIN"), null)
                .body().path("data").path("content").get(0);
        assertThat(month.path("pgCostStatus").asText()).isEqualTo("PARTIAL");
        assertThat(month.path("confirmedPgFeeAmount").asLong()).isZero();
        assertThat(month.path("pgFeeAmount").isNull()).isTrue();
        assertThat(month.path("pendingPgCostCount").asLong()).isEqualTo(1);
        provider.put(pendingOrder.paymentId(), withFee(pending, 0));
        synchronize.execute(pendingOrder.paymentId(), "usr_admin");
        JsonNode confirmed = http("GET", "/api/v1/settlements/months", token("usr_admin", "ADMIN"), null)
                .body().path("data").path("content").get(0);
        assertThat(confirmed.path("pgCostStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("pgFeeAmount").asLong()).isZero();
        assertThat(confirmed.path("platformNetAmount").asLong()).isEqualTo(2000);
        assertThat(confirmed.path("month").path("sellerPayoutAmount").asLong()).isEqualTo(18000);
    }

}
