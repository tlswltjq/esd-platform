package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.id;
import static com.stove.settlement.SettlementFixtures.order;
import static com.stove.settlement.SettlementFixtures.payment;
import static com.stove.settlement.SettlementFixtures.report;
import static com.stove.settlement.SettlementRefundFixtures.confirmation;
import static com.stove.settlement.SettlementRefundFixtures.proof;
import static com.stove.settlement.SettlementRefundFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
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
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.common.kafka.contract.payload.RefundExecutionSucceededEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.common.test.PausePoints;
import com.stove.common.test.PausePoints.Gate;
import com.stove.common.test.PausePoints.Run;
import com.stove.common.test.PausePoints.Watch;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.api.listener.SettlementOrderMapper;
import com.stove.settlement.api.listener.SettlementRefundMapper;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.domain.PgRefundCancellation;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.port.PgRefunds;
import com.stove.settlement.core.service.RecordConfirmedRefund;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.FullRefundAllocationPolicy;
import com.stove.settlement.core.domain.RefundAllocation;
import com.stove.settlement.core.domain.RefundMonthInput;
import com.stove.settlement.core.domain.RefundMonthSelection;
import com.stove.settlement.core.domain.RefundSettlementMonthPolicy;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.service.SynchronizePgTransaction;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgReceiptView;
import com.stove.settlement.core.domain.PgSettlement;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
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
import java.util.Arrays;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
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
    static final PausePoints PAUSES=new PausePoints();
    static final String MONTH_LOCKED="SellerMonthlySettlementRepository.lock";
    static final Duration BLOCKED=Duration.ofSeconds(1);
    @TestConfiguration
    static class Pausing {
        @Bean static BeanPostProcessor pausingMonthRepository() { return PausePoints.pausing(PAUSES,SellerMonthlySettlementRepository.class); }
    }
    @AfterEach void unusedPauses() { assertThat(PAUSES.reset()).as("쓰이지 않은 멈춤 지점").isEmpty(); }
    @Autowired RecordPaidSale record;
    @Autowired RecordConfirmedRefund recordRefund;
    @Autowired FullRefundAllocationPolicy fullRefundAllocationPolicy;
    @Autowired RefundSettlementMonthPolicy refundSettlementMonthPolicy;
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
    @MockitoBean PgRefunds pgRefunds;
    @MockitoBean CatalogOwners owners;
    @MockitoBean Clock clock;
    @MockitoSpyBean ProcessedEventGuard guard;
    @LocalServerPort int port;
    private final Map<String,PgPayment> provider=new ConcurrentHashMap<String,PgPayment>();
    private final Map<String,PgRefund> refundProvider=new ConcurrentHashMap<>();

    ConfirmedRefund prepareRefund(PaidOrder input) {
        PgPayment payment=provider.get(input.paymentId());ConfirmedRefund confirmed=confirmation(input,payment);
        refundProvider.put(confirmed.refundId(),proof(payment,confirmed));return confirmed;
    }
    @Test void aConfirmedRefundIsAtomicAndVisibleInCreatorAndAdministratorQueries() {
        PaidOrder input=order();SettlementSale original=sale(input);ConfirmedRefund confirmed=prepareRefund(input);
        Map<String,Object> originalRow=jdbc.queryForMap("SELECT * FROM settlement_sale");
        SettlementRefund refund=recordRefund.execute(confirmed);
        assertThat(refund.getOrderNo()).isEqualTo(original.getOrderNo());
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_sale")).isEqualTo(originalRow);
        assertThat(count("settlement_refund")).isEqualTo(1);assertThat(count("settlement_refund_cancellation")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refund_count FROM seller_monthly_settlement",Long.class)).isEqualTo(1);
        JsonNode month=http("GET","/api/v1/settlements/me/months",token("usr_seller","CREATOR"),null).body().path("data").path("content").get(0);
        assertThat(month.path("paidAmount").asLong()).isEqualTo(10000);assertThat(month.path("refundAmount").asLong()).isEqualTo(10000);
        assertThat(month.path("netSellerPayoutAmount").asLong()).isZero();
        Reply mine=http("GET","/api/v1/settlements/me/refunds",token("usr_seller","CREATOR"),null);
        assertThat(mine.status()).isEqualTo(200);assertThat(mine.body().toString()).doesNotContain("buyerSubject","cancellationIds","attemptId","paymentId");
        assertThat(mine.body().path("data").path("content").get(0).path("sellerPayoutAmountDelta").asLong()).isEqualTo(-9000);
        assertThat(http("GET","/api/v1/settlements/me/refunds",token("usr_other","CREATOR"),null).body().path("data").path("totalElements").asLong()).isZero();
        String detail="/api/v1/settlements/refunds/"+confirmed.refundId();String admin=token("usr_admin","ADMIN");
        assertThat(http("GET",detail,admin,null).body().path("data").path("source").path("cancellationIds").size()).isEqualTo(1);
        assertThat(http("GET",detail,token("usr_seller","CREATOR"),null).status()).isEqualTo(403);
        assertThat(http("GET",detail,null,null).status()).isEqualTo(401);
        assertThat(http("GET","/api/v1/settlements/refunds/"+id(),admin,null).status()).isEqualTo(404);
        assertThat(http("GET","/api/v1/settlements/refunds/bad",admin,null).status()).isEqualTo(400);
        assertThat(http("GET","/api/v1/settlements/me/refunds?size=0",token("usr_seller","CREATOR"),null).status()).isEqualTo(400);
        JsonNode finance=http("GET","/api/v1/settlements/months",admin,null).body().path("data").path("content").get(0);
        assertThat(finance.path("pgCostStatus").asText()).isEqualTo("PARTIAL");
        assertThat(finance.path("salePgFeeAmount").asLong()).isEqualTo(300);
        assertThat(finance.path("pgFeeAmount").isNull()).isTrue();assertThat(finance.path("platformNetAmount").isNull()).isTrue();
    }
    @Test void refundAfterSaleMonthCloseUsesConfirmationMonthAndDuplicatesKeepTheFirstMonth() {
        PaidOrder input=order(Instant.parse("2026-09-01T00:00:00Z"));sale(input);close.execute("usr_seller","2026-09");
        Map<String,Object> closedSeptember=jdbc.queryForMap("SELECT * FROM seller_monthly_settlement WHERE settlement_month='2026-09'");
        ConfirmedRefund confirmed=prepareRefund(input);SettlementRefund first=recordRefund.execute(confirmed);
        assertThat(first.getSettlementMonth()).isEqualTo("2026-10");
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement WHERE settlement_month='2026-09'")).isEqualTo(closedSeptember);
        assertThat(jdbc.queryForObject("SELECT seller_payout_amount-refunded_seller_payout_amount FROM seller_monthly_settlement WHERE settlement_month='2026-10'",Long.class)).isEqualTo(-9000);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-02T00:00:00Z"));
        SellerMonthlySettlement october=close.execute("usr_seller","2026-10");assertThat(october.getNetSellerPayoutAmount()).isEqualTo(-9000);
        Map<String,Object> closedOctober=jdbc.queryForMap("SELECT * FROM seller_monthly_settlement WHERE settlement_month='2026-10'");
        when(pgRefunds.require(any(PgPayment.class),any(ConfirmedRefund.class))).thenThrow(new IllegalStateException("PG unavailable after posting"));
        assertThat(recordRefund.execute(confirmed).getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement WHERE settlement_month='2026-10'")).isEqualTo(closedOctober);
        assertThat(count("seller_monthly_settlement")).isEqualTo(2);
    }
    @Test void delayedRefundUsesTheStillOpenConfirmationMonth() { delayedRefund(false); }
    @Test void delayedRefundUsesTheCurrentMonthAfterConfirmationMonthClosed() { delayedRefund(true); }
    void delayedRefund(boolean closed) {
        PaidOrder input=order(Instant.parse("2026-09-01T00:00:00Z"));sale(input);ConfirmedRefund fresh=prepareRefund(input);
        if(closed) close.execute("usr_seller","2026-09");
        ConfirmedRefund delayed=new ConfirmedRefund(fresh.eventId(),Instant.parse("2026-09-29T00:00:00Z"),fresh.refundId(),fresh.attemptId(),fresh.requestEventId(),
                fresh.orderNo(),fresh.paymentId(),fresh.buyerSubject(),fresh.amount(),fresh.currency(),fresh.source(),fresh.providerTransactionId(),fresh.cancellationIds());
        refundProvider.put(delayed.refundId(),proof(provider.get(input.paymentId()),delayed));
        SettlementRefund refund=recordRefund.execute(delayed);
        assertThat(refund.getRefundMonth()).isEqualTo("2026-09");assertThat(refund.getSettlementMonth()).isEqualTo(closed?"2026-10":"2026-09");
    }
    @Test void salesOfAClosedMonthShowTheirRolledOverRefundLinkForCreatorAndAdministrator() {
        PaidOrder refundedOrder=order(Instant.parse("2026-09-01T00:00:00Z"));sale(refundedOrder);
        PaidOrder keptOrder=order(Instant.parse("2026-09-02T00:00:00Z"));sale(keptOrder);
        close.execute("usr_seller","2026-09");
        ConfirmedRefund fresh=prepareRefund(refundedOrder);
        ConfirmedRefund confirmed=new ConfirmedRefund(fresh.eventId(),Instant.parse("2026-09-29T00:00:00Z"),fresh.refundId(),fresh.attemptId(),fresh.requestEventId(),
                fresh.orderNo(),fresh.paymentId(),fresh.buyerSubject(),fresh.amount(),fresh.currency(),fresh.source(),fresh.providerTransactionId(),fresh.cancellationIds());
        refundProvider.put(confirmed.refundId(),proof(provider.get(refundedOrder.paymentId()),confirmed));
        SettlementRefund refund=recordRefund.execute(confirmed);
        assertThat(refund.getSettlementMonth()).isEqualTo("2026-10");

        JsonNode mine=http("GET","/api/v1/settlements/me/sales?month=2026-09",token("usr_seller","CREATOR"),null).body().path("data").path("content");
        assertThat(mine.size()).isEqualTo(2);
        JsonNode kept=mine.get(0);JsonNode refunded=mine.get(1);
        assertThat(kept.path("orderNo").asText()).isEqualTo(keptOrder.orderNo());assertThat(kept.path("refund").isNull()).isTrue();
        assertThat(refunded.path("orderNo").asText()).isEqualTo(refundedOrder.orderNo());
        assertThat(refunded.path("settlementMonth").asText()).isEqualTo("2026-09");
        JsonNode link=refunded.path("refund");
        assertThat(link.path("refundId").asText()).isEqualTo(confirmed.refundId());
        assertThat(link.path("refundMonth").asText()).isEqualTo("2026-09");assertThat(link.path("settlementMonth").asText()).isEqualTo("2026-10");
        assertThat(link.path("monthReason").asText()).isEqualTo("CLOSED_MONTH_ROLLOVER");
        assertThat(Instant.parse(link.path("confirmedAt").asText())).isEqualTo(confirmed.occurredAt());
        assertThat(mine.toString()).doesNotContain("buyerSubject","paymentId","cancellationIds","sellerSubject");
        JsonNode linkedRefund=http("GET","/api/v1/settlements/me/refunds?month=2026-10",token("usr_seller","CREATOR"),null).body().path("data").path("content").get(0);
        assertThat(linkedRefund.path("refundId").asText()).isEqualTo(link.path("refundId").asText());
        assertThat(linkedRefund.path("orderNo").asText()).isEqualTo(refundedOrder.orderNo());
        assertThat(http("GET","/api/v1/settlements/me/sales",token("usr_other","CREATOR"),null).body().path("data").path("totalElements").asLong()).isZero();

        JsonNode admin=http("GET","/api/v1/settlements/sales?sellerSubject=usr_seller&month=2026-09",token("usr_admin","ADMIN"),null).body().path("data").path("content");
        assertThat(admin.get(0).path("sale").path("refund").isNull()).isTrue();
        assertThat(admin.get(1).path("sale").path("refund")).isEqualTo(link);
    }
    @Test void concurrentRefundDeliveriesPostOneReversal() throws Exception {
        PaidOrder input=order();sale(input);ConfirmedRefund confirmed=prepareRefund(input);
        try(ExecutorService pool=Executors.newFixedThreadPool(4)) {
            List<Future<?>> calls=new ArrayList<>();CountDownLatch start=new CountDownLatch(1);
            for(int i=0;i<4;i++) calls.add(pool.submit(() -> {start.await();return recordRefund.execute(confirmed);}));
            start.countDown();for(Future<?> call:calls) call.get(30,TimeUnit.SECONDS);
        }
        assertThat(count("settlement_refund")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT refund_count FROM seller_monthly_settlement",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refunded_seller_payout_amount FROM seller_monthly_settlement",Long.class)).isEqualTo(9000);
    }
    @Test void refundRacingWithCloseChoosesOneConsistentMonth() throws Exception {
        PaidOrder input=order(Instant.parse("2026-09-01T00:00:00Z"));sale(input);ConfirmedRefund fresh=prepareRefund(input);
        ConfirmedRefund confirmed=new ConfirmedRefund(fresh.eventId(),Instant.parse("2026-09-29T00:00:00Z"),fresh.refundId(),fresh.attemptId(),fresh.requestEventId(),
                fresh.orderNo(),fresh.paymentId(),fresh.buyerSubject(),fresh.amount(),fresh.currency(),fresh.source(),fresh.providerTransactionId(),fresh.cancellationIds());
        refundProvider.put(confirmed.refundId(),proof(provider.get(input.paymentId()),confirmed));
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            Future<?> a=pool.submit(() -> close.execute("usr_seller","2026-09"));Future<?> b=pool.submit(() -> recordRefund.execute(confirmed));
            a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
        }
        assertThat(count("settlement_refund")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(seller_payout_amount-refunded_seller_payout_amount) FROM seller_monthly_settlement",Long.class)).isZero();
        SellerMonthlySettlement closed=close.execute("usr_seller","2026-09");
        long actual=jdbc.queryForObject("SELECT COALESCE(SUM(seller_payout_amount),0) FROM settlement_refund WHERE settlement_month='2026-09'",Long.class);
        assertThat(closed.getRefundedSellerPayoutAmount()).isEqualTo(actual);
    }
    // 결정 103: 월 장부 잠금을 빼도 위 경합 테스트는 통과했다(마감이 거의 항상 먼저 끝난다). 순서를 멈춤 지점으로 고정한다.
    @Test void closeWaitsForARefundHoldingTheMonthLockAndIncludesIt() throws Exception {
        PaidOrder input=order(Instant.parse("2026-09-01T00:00:00Z"));sale(input);ConfirmedRefund confirmed=septemberRefund(input);
        Gate locked=PAUSES.arm(MONTH_LOCKED,"refund");
        Run<SettlementRefund> refunding=PausePoints.start("refund",() -> recordRefund.execute(confirmed));
        locked.awaitArrival();
        Watch waiting=PAUSES.watch(MONTH_LOCKED,"close");
        Run<SellerMonthlySettlement> closing=PausePoints.start("close",() -> close.execute("usr_seller","2026-09"));
        assertThat(waiting.stillInsideAfter(BLOCKED)).as("환불이 월을 잠근 동안 마감은 잠금 호출 안에서 기다린다").isTrue();
        locked.release();
        assertThat(refunding.join().getSettlementMonth()).isEqualTo("2026-09");
        SellerMonthlySettlement closed=closing.join();
        long refunded=jdbc.queryForObject("SELECT SUM(seller_payout_amount) FROM settlement_refund WHERE settlement_month='2026-09'",Long.class);
        assertThat(refunded).isPositive();
        assertThat(closed.getRefundedSellerPayoutAmount()).isEqualTo(refunded);
    }
    @Test void refundWaitsForACloseHoldingTheMonthLockAndMovesToTheOpenMonth() throws Exception {
        PaidOrder input=order(Instant.parse("2026-09-01T00:00:00Z"));sale(input);ConfirmedRefund confirmed=septemberRefund(input);
        Gate locked=PAUSES.arm(MONTH_LOCKED,"close");
        Run<SellerMonthlySettlement> closing=PausePoints.start("close",() -> close.execute("usr_seller","2026-09"));
        locked.awaitArrival();
        Watch waiting=PAUSES.watch(MONTH_LOCKED,"refund");
        Run<SettlementRefund> refunding=PausePoints.start("refund",() -> recordRefund.execute(confirmed));
        assertThat(waiting.stillInsideAfter(BLOCKED)).as("마감이 월을 잠근 동안 환불은 잠금 호출 안에서 기다린다").isTrue();
        locked.release();
        assertThat(closing.join().getRefundedSellerPayoutAmount()).isZero();
        assertThat(refunding.join().getSettlementMonth()).isNotEqualTo("2026-09");
        assertThat(close.execute("usr_seller","2026-09").getRefundedSellerPayoutAmount()).isZero();
    }
    @Test void closeWaitsForASaleHoldingTheMonthLockAndIncludesIt() throws Exception {
        sale(order(Instant.parse("2026-09-01T00:00:00Z")));
        PaidOrder racing=order(Instant.parse("2026-09-02T00:00:00Z"));prepare(racing);
        Gate locked=PAUSES.arm(MONTH_LOCKED,"sale");
        Run<SettlementSale> recording=PausePoints.start("sale",() -> record.execute(racing));
        locked.awaitArrival();
        Watch waiting=PAUSES.watch(MONTH_LOCKED,"close");
        Run<SellerMonthlySettlement> closing=PausePoints.start("close",() -> close.execute("usr_seller","2026-09"));
        assertThat(waiting.stillInsideAfter(BLOCKED)).as("매출이 월을 잠근 동안 마감은 잠금 호출 안에서 기다린다").isTrue();
        locked.release();
        assertThat(recording.join().getSettlementMonth()).isEqualTo("2026-09");
        assertThat(closing.join().getPaidAmount()).isEqualTo(20000);
    }
    private ConfirmedRefund septemberRefund(PaidOrder input) {
        ConfirmedRefund fresh=prepareRefund(input);
        ConfirmedRefund confirmed=new ConfirmedRefund(fresh.eventId(),Instant.parse("2026-09-29T00:00:00Z"),fresh.refundId(),fresh.attemptId(),fresh.requestEventId(),
                fresh.orderNo(),fresh.paymentId(),fresh.buyerSubject(),fresh.amount(),fresh.currency(),fresh.source(),fresh.providerTransactionId(),fresh.cancellationIds());
        refundProvider.put(confirmed.refundId(),proof(provider.get(input.paymentId()),confirmed));
        return confirmed;
    }
    @Test void supplierMismatchAndRollbackCannotLeaveHalfAPosting() {
        PaidOrder input=order();sale(input);ConfirmedRefund confirmed=prepareRefund(input);
        PgPayment payment=provider.get(input.paymentId());
        refundProvider.put(confirmed.refundId(),new PgRefund(payment,List.of(new PgRefundCancellation("SIMULATED",id(),10000,NOW)),NOW));
        assertThatThrownBy(() -> recordRefund.execute(confirmed)).isInstanceOf(BusinessException.class);
        assertThat(count("settlement_refund")).isZero();
        refundProvider.put(confirmed.refundId(),proof(payment,confirmed));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {call.callRealMethod();throw new IllegalStateException("after refund inbox");})
                .when(guard).firstDelivery(anyString(),anyString(),anyString()));
        assertThatThrownBy(() -> recordRefund.execute(confirmed)).isInstanceOf(IllegalStateException.class);
        assertThat(count("settlement_refund")).isZero();assertThat(count("settlement_refund_cancellation")).isZero();
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM seller_monthly_settlement",Long.class)).isZero();
        assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void reusedCancellationCannotReverseAnotherOrder() {
        PaidOrder first=order();sale(first);ConfirmedRefund confirmed=prepareRefund(first);recordRefund.execute(confirmed);
        PaidOrder second=order();sale(second);ConfirmedRefund fresh=prepareRefund(second);
        ConfirmedRefund reused=new ConfirmedRefund(fresh.eventId(),fresh.occurredAt(),fresh.refundId(),fresh.attemptId(),fresh.requestEventId(),fresh.orderNo(),fresh.paymentId(),
                fresh.buyerSubject(),fresh.amount(),fresh.currency(),fresh.source(),fresh.providerTransactionId(),confirmed.cancellationIds());
        refundProvider.put(reused.refundId(),proof(provider.get(second.paymentId()),reused));
        assertThatThrownBy(() -> recordRefund.execute(reused)).isInstanceOf(BusinessException.class);
        assertThat(count("settlement_refund")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT refund_count FROM seller_monthly_settlement",Long.class)).isEqualTo(1);
    }

    @Test void configuredRefundMonthPolicyUsesLockedStatesWithoutChangingTheClosedLedger() {
        SettlementSale original = sale(order(Instant.parse("2026-09-01T00:00:00Z")));
        sale(order()); close.execute("usr_seller", "2026-09");
        List<Map<String, Object>> beforeSales = jdbc.queryForList("SELECT * FROM settlement_sale ORDER BY id");
        List<Map<String, Object>> beforeMonths = jdbc.queryForList("SELECT * FROM seller_monthly_settlement ORDER BY id");
        int beforeInbox = count("processed_event");
        RefundMonthSelection selected = new TransactionTemplate(transactions).execute(tx -> {
            boolean confirmedClosed = jdbc.queryForObject("SELECT status='CLOSED' FROM seller_monthly_settlement WHERE seller_subject='usr_seller' AND settlement_month='2026-09' FOR UPDATE", Boolean.class);
            boolean currentClosed = jdbc.queryForObject("SELECT status='CLOSED' FROM seller_monthly_settlement WHERE seller_subject='usr_seller' AND settlement_month='2026-10' FOR UPDATE", Boolean.class);
            return refundSettlementMonthPolicy.select(new RefundMonthInput(id(), original.getOrderNo(),
                    Instant.parse("2026-09-29T00:00:00Z"), NOW, confirmedClosed, currentClosed, null));
        });
        assertThat(selected.refundMonth()).isEqualTo("2026-09");
        assertThat(selected.settlementMonth()).isEqualTo("2026-10");
        assertThat(selected.reason()).isEqualTo("CLOSED_MONTH_ROLLOVER");
        assertThat(jdbc.queryForList("SELECT * FROM settlement_sale ORDER BY id")).isEqualTo(beforeSales);
        assertThat(jdbc.queryForList("SELECT * FROM seller_monthly_settlement ORDER BY id")).isEqualTo(beforeMonths);
        assertThat(count("processed_event")).isEqualTo(beforeInbox);
    }

    @Test void configuredRefundPolicyUsesTheStoredAllocationWithoutChangingAnyLedgerRows() {
        SettlementSale original = sale(order());
        Map<String, Object> beforeSale = jdbc.queryForMap("SELECT * FROM settlement_sale WHERE id=?", original.getId());
        Map<String, Object> beforeMonth = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        int beforeInbox = count("processed_event");
        RefundAllocation allocation = fullRefundAllocationPolicy.allocate(original);
        assertThat(allocation.paidAmountDelta()).isEqualTo(-10000);
        assertThat(allocation.platformFeeAmountDelta()).isEqualTo(-1000);
        assertThat(allocation.sellerPayoutAmountDelta()).isEqualTo(-9000);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_sale WHERE id=?", original.getId())).isEqualTo(beforeSale);
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(beforeMonth);
        assertThat(count("processed_event")).isEqualTo(beforeInbox);
    }

    @BeforeEach void clean() {
        listeners.stop();
        for(String table:List.of("settlement_pg_cost_posting","settlement_pg_statement_item","settlement_pg_statement_page","settlement_pg_statement","settlement_pg_statement_entry",
                "settlement_refund_cancellation","settlement_refund","pg_receipt_item","pg_receipt","settlement_sale","settlement_seller_carryover","seller_monthly_settlement","settlement_pg_fee","settlement_pg_transaction","processed_event"))
            jdbc.update("DELETE FROM "+table);
        provider.clear(); when(clock.instant()).thenReturn(NOW);
        refundProvider.clear();
        when(pgRefunds.require(any(PgPayment.class),any(ConfirmedRefund.class))).thenAnswer(call -> {
            PgRefund refund=refundProvider.get(call.getArgument(1,ConfirmedRefund.class).refundId());
            if(refund==null) throw new IllegalStateException("PG refund fixture unavailable");return refund;
        });
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
    // 결정 121·130: 판매자 월 마감 순서 강제와 음수 순액 이월
    PaidOrder paid(Instant at,long amount) {
        return new PaidOrder(id(),at,id(),id(),id(),id(),"usr_buyer",1L,"game-1","Frozen title",1,amount,"KRW");
    }
    Reply closeMonth(String month) { return http("POST","/api/v1/settlements/sellers/usr_seller/months/"+month+"/close",token("usr_admin","ADMIN"),null); }
    JsonNode adminMonth(String month) {
        JsonNode content=http("GET","/api/v1/settlements/months?sellerSubject=usr_seller&month="+month,token("usr_admin","ADMIN"),null).body().path("data").path("content");
        assertThat(content.size()).as(month).isEqualTo(1);return content.get(0).path("month");
    }
    static void balance(JsonNode month,long carriedIn,long payable,long carriedOut) {
        assertThat(List.of(month.path("carriedIn").isIntegralNumber(),month.path("payable").isIntegralNumber(),month.path("carriedOut").isIntegralNumber()))
                .as(month.path("settlementMonth").asText()).containsOnly(true);
        assertThat(List.of(month.path("carriedIn").asLong(),month.path("payable").asLong(),month.path("carriedOut").asLong()))
                .as(month.path("settlementMonth").asText()).containsExactly(carriedIn,payable,carriedOut);
    }
    static void unsettled(JsonNode month) {
        for(String field:List.of("carriedIn","payable","carriedOut")) assertThat(month.path(field).isNull()).as(field).isTrue();
    }
    JsonNode closed(String month) {
        Reply reply=closeMonth(month);assertThat(reply.status()).as(month).isEqualTo(200);
        JsonNode result=reply.body().path("data").path("month");assertThat(result.path("status").asText()).isEqualTo("CLOSED");return result;
    }
    void at(String instant) { when(clock.instant()).thenReturn(Instant.parse(instant)); }
    Map<String,Object> carryover(String month) {
        return jdbc.queryForMap("SELECT * FROM settlement_seller_carryover WHERE seller_subject='usr_seller' AND settlement_month=?",month);
    }

    @Test void negativeNetCarriesForwardOnlyThroughClosingsInMonthOrder() {
        PaidOrder september=paid(Instant.parse("2026-09-01T00:00:00Z"),9000);sale(september);
        balance(closed("2026-09"),0,8100,0);
        recordRefund.execute(prepareRefund(september));sale(paid(NOW.minusSeconds(20),5000));
        JsonNode octoberOpen=adminMonth("2026-10");
        assertThat(octoberOpen.path("netSellerPayoutAmount").asLong()).isEqualTo(-3600);unsettled(octoberOpen);
        at("2026-11-15T03:00:00Z");sale(paid(Instant.parse("2026-11-15T02:00:00Z"),3000));
        at("2026-12-02T03:00:00Z");
        Reply outOfOrder=closeMonth("2026-11");Reply current=closeMonth("2026-12");
        assertThat(outOfOrder.status()).isEqualTo(409);assertThat(current.status()).isEqualTo(409);
        assertThat(outOfOrder.body().path("success").asBoolean()).isFalse();
        assertThat(outOfOrder.body().path("error").path("code").asText()).isEqualTo(current.body().path("error").path("code").asText());
        assertThat(adminMonth("2026-11").path("status").asText()).isEqualTo("OPEN");
        assertThat(count("settlement_seller_carryover")).isEqualTo(1);
        balance(closed("2026-10"),0,0,-3600);
        balance(closed("2026-11"),-3600,0,-900);
        at("2026-12-15T03:00:00Z");sale(paid(Instant.parse("2026-12-15T02:00:00Z"),2000));
        at("2027-01-02T03:00:00Z");
        balance(closed("2026-12"),-900,900,0);
        assertThat(carryover("2026-09").get("source_month")).isNull();
        assertThat(carryover("2026-10").get("source_month")).isEqualTo("2026-09");
        assertThat(carryover("2026-11").get("source_month")).isEqualTo("2026-10");
        assertThat(carryover("2026-11").get("net_seller_payout_amount")).isEqualTo(2700L);
        assertThat(carryover("2026-12").get("source_month")).isEqualTo("2026-11");
        List<Map<String,Object>> ledger=jdbc.queryForList("SELECT * FROM settlement_seller_carryover ORDER BY id");
        Map<String,Object> novemberRow=jdbc.queryForMap("SELECT * FROM seller_monthly_settlement WHERE settlement_month='2026-11'");
        balance(closed("2026-11"),-3600,0,-900);
        assertThat(jdbc.queryForList("SELECT * FROM settlement_seller_carryover ORDER BY id")).isEqualTo(ledger);
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement WHERE settlement_month='2026-11'")).isEqualTo(novemberRow);
        JsonNode mine=http("GET","/api/v1/settlements/me/months?month=2026-11",token("usr_seller","CREATOR"),null).body().path("data").path("content").get(0);
        balance(mine,-3600,0,-900);
    }
    @Test void monthsWithoutRowsAreSkippedWhenFindingTheCarry() {
        PaidOrder september=order(Instant.parse("2026-09-01T00:00:00Z"));sale(september);closed("2026-09");
        recordRefund.execute(prepareRefund(september));
        at("2026-11-02T03:00:00Z");balance(closed("2026-10"),0,0,-9000);
        at("2026-12-15T03:00:00Z");sale(paid(Instant.parse("2026-12-15T02:00:00Z"),20000));
        at("2027-01-02T03:00:00Z");
        balance(closed("2026-12"),-9000,9000,0);
        assertThat(carryover("2026-12").get("source_month")).isEqualTo("2026-10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seller_monthly_settlement WHERE settlement_month='2026-11'",Long.class)).isZero();
    }
    /** R3.4: re-closing a CLOSED month returns its first result, even when an earlier month is OPEN (pre-V13 out-of-order data or a late event). */
    @Test void reclosingAClosedMonthReturnsItsFirstResultEvenWhenAnEarlierMonthIsOpen() {
        sale(order(Instant.parse("2026-09-01T00:00:00Z")));closed("2026-09");
        at("2026-11-02T03:00:00Z");JsonNode first=closed("2026-10");
        jdbc.update("UPDATE seller_monthly_settlement SET status='OPEN',closed_at=NULL WHERE settlement_month='2026-09'");
        assertThat(closed("2026-10")).isEqualTo(first);
        assertThat(count("settlement_seller_carryover")).isEqualTo(2);
        assertThat(closeMonth("2026-11").status()).isEqualTo(409);
    }
    @Test void closedMonthsWithoutLedgerAreReadAsCarriedInZeroAndStillHandOverTheirNegativeNet() {
        PaidOrder september=order(Instant.parse("2026-09-01T00:00:00Z"));sale(september);closed("2026-09");
        recordRefund.execute(prepareRefund(september));
        at("2026-11-02T03:00:00Z");closed("2026-10");
        jdbc.update("DELETE FROM settlement_seller_carryover");
        balance(adminMonth("2026-09"),0,9000,0);balance(adminMonth("2026-10"),0,0,-9000);
        JsonNode mine=http("GET","/api/v1/settlements/me/months?month=2026-10",token("usr_seller","CREATOR"),null).body().path("data").path("content").get(0);
        balance(mine,0,0,-9000);
        sale(paid(Instant.parse("2026-11-02T02:00:00Z"),5000));
        at("2026-12-02T03:00:00Z");
        balance(closed("2026-11"),-9000,0,-4500);
        assertThat(carryover("2026-11").get("source_month")).isEqualTo("2026-10");
        assertThat(count("settlement_seller_carryover")).isEqualTo(1);
    }
    @Test void concurrentClosingsOfOneMonthWriteOneLedgerRow() throws Exception {
        sale(order(Instant.parse("2026-09-01T00:00:00Z")));CountDownLatch start=new CountDownLatch(1);
        List<Future<SellerMonthlySettlement>> calls=new ArrayList<>();
        try(ExecutorService pool=Executors.newFixedThreadPool(4)) {
            for(int i=0;i<4;i++) calls.add(pool.submit(() -> { start.await();return close.execute("usr_seller","2026-09"); }));
            start.countDown();
            for(Future<SellerMonthlySettlement> call:calls) assertThat(call.get(30,TimeUnit.SECONDS).isClosed()).isTrue();
        }
        assertThat(count("settlement_seller_carryover")).isEqualTo(1);
        assertThat(carryover("2026-09").get("payable")).isEqualTo(9000L);
    }
    @Test void closingALaterMonthWhileAnEarlierCloseHoldsItsLockIsRejected() throws Exception {
        sale(order(Instant.parse("2026-09-01T00:00:00Z")));sale(order(NOW.minusSeconds(20)));
        at("2026-11-02T03:00:00Z");
        Gate locked=PAUSES.arm(MONTH_LOCKED,"close");
        Run<SellerMonthlySettlement> closing=PausePoints.start("close",() -> close.execute("usr_seller","2026-09"));
        locked.awaitArrival();
        assertThatThrownBy(() -> close.execute("usr_seller","2026-10")).isInstanceOf(BusinessException.class);
        locked.release();
        assertThat(closing.join().isClosed()).isTrue();
        balance(closed("2026-10"),0,9000,0);
        assertThat(carryover("2026-10").get("source_month")).isEqualTo("2026-09");
    }

    // 결정 42·130: 같은 판매자의 뒤 월이 CLOSED면 그 앞 월(행이 없어도)은 닫힌 것으로 본다(D-045·D-046).
    static final Instant SEPTEMBER_30_KST=Instant.parse("2026-09-30T14:50:00Z");
    /** 8/20 매출 9,000원(판매자 +8,100) → 9/2 8월 마감 → 10/15 매출 5,000원(+4,500). 9월은 활동이 없어 행이 없다. */
    PaidOrder augustClosedAndOctoberSold() {
        at("2026-08-20T03:00:00Z");PaidOrder august=paid(Instant.parse("2026-08-20T02:00:00Z"),9000);sale(august);
        at("2026-09-02T03:00:00Z");balance(closed("2026-08"),0,8100,0);
        at("2026-10-15T03:00:00Z");sale(paid(Instant.parse("2026-10-15T02:00:00Z"),5000));
        return august;
    }
    /** 위에 더해 11/2 10월 마감(출처 8월, payable 4,500). */
    PaidOrder augustAndOctoberClosed() {
        PaidOrder august=augustClosedAndOctoberSold();
        at("2026-11-02T03:00:00Z");balance(closed("2026-10"),0,4500,0);
        assertThat(carryover("2026-10").get("source_month")).isEqualTo("2026-08");
        return august;
    }
    ConfirmedRefund refundConfirmedAt(PaidOrder input,Instant confirmedAt) {
        ConfirmedRefund fresh=prepareRefund(input);
        ConfirmedRefund confirmed=new ConfirmedRefund(fresh.eventId(),confirmedAt,fresh.refundId(),fresh.attemptId(),fresh.requestEventId(),
                fresh.orderNo(),fresh.paymentId(),fresh.buyerSubject(),fresh.amount(),fresh.currency(),fresh.source(),fresh.providerTransactionId(),fresh.cancellationIds());
        refundProvider.put(confirmed.refundId(),proof(provider.get(input.paymentId()),confirmed));
        return confirmed;
    }
    long monthRows(String month) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM seller_monthly_settlement WHERE seller_subject='usr_seller' AND settlement_month=?",Long.class,month);
    }
    String monthStatus(String month) {
        return jdbc.queryForObject("SELECT status FROM seller_monthly_settlement WHERE seller_subject='usr_seller' AND settlement_month=?",String.class,month);
    }
    /**
     * [D-045] 기대: 10월이 CLOSED라 9/30 확인 환불은 11월에 반영되고 9월 행은 생기지 않으며, 11월 마감이 8,100원을 상계해 carriedOut −5,400.
     * 실제(수정 전): 9월 OPEN 행이 생겨 환불이 9월(CONFIRMED_MONTH)에 붙고 11월 마감은 409, 9월을 먼저 마감해도 11월은 출처 10월이라 8,100원을 과지급한다.
     */
    @Test @Tag("known-defect") void lateRefundOfAMonthWithoutRowBeforeALaterClosedMonthMovesToTheCurrentMonth() {
        PaidOrder august=augustAndOctoberClosed();
        at("2026-11-03T03:00:00Z");
        SettlementRefund refund=recordRefund.execute(refundConfirmedAt(august,SEPTEMBER_30_KST));
        assertThat(List.of(refund.getRefundMonth(),refund.getSettlementMonth(),refund.getMonthReason()))
                .containsExactly("2026-09","2026-11","CLOSED_MONTH_ROLLOVER");
        assertThat(monthRows("2026-09")).isZero();
        at("2026-11-20T03:00:00Z");sale(paid(Instant.parse("2026-11-20T02:00:00Z"),3000));
        at("2026-12-01T03:00:00Z");
        balance(closed("2026-11"),0,0,-5400);
        assertThat(carryover("2026-11").get("source_month")).isEqualTo("2026-10");
        assertThat(monthRows("2026-09")).isZero();
    }
    /** [D-045] 같은 흐름의 매출: 9/30 매출이 11/3에 도착하면 saleMonth 9월을 보존한 채 11월에 반영되고 9월 행은 생기지 않는다. */
    @Test @Tag("known-defect") void lateSaleOfAMonthWithoutRowBeforeALaterClosedMonthMovesToTheCurrentMonth() {
        augustAndOctoberClosed();
        at("2026-11-03T03:00:00Z");
        SettlementSale late=sale(paid(SEPTEMBER_30_KST,5000));
        assertThat(List.of(late.getSaleMonth(),late.getSettlementMonth())).containsExactly("2026-09","2026-11");
        assertThat(monthRows("2026-09")).isZero();
        at("2026-12-01T03:00:00Z");
        JsonNode november=closed("2026-11");
        balance(november,0,4500,0);assertThat(november.path("paidAmount").asLong()).isEqualTo(5000);
        assertThat(monthRows("2026-09")).isZero();
    }
    /**
     * [D-046] 기대: 12월이 출처 10월(−9,000)을 받아 마감된 뒤 행 없는 11월 마감은 409이고 11월 행·원장 행이 생기지 않는다.
     * 실제(수정 전): 0원 11월 CLOSED 행이 생기고 출처 10월의 −9,000을 다시 받아, 같은 이월을 두 번 쓴다.
     */
    @Test @Tag("known-defect") void closingAnEarlierMonthAfterALaterMonthClosedIsRejectedWithoutReusingTheCarry() {
        PaidOrder september=order(Instant.parse("2026-09-01T00:00:00Z"));sale(september);closed("2026-09");
        recordRefund.execute(prepareRefund(september));
        at("2026-11-02T03:00:00Z");balance(closed("2026-10"),0,0,-9000);
        at("2026-12-15T03:00:00Z");sale(paid(Instant.parse("2026-12-15T02:00:00Z"),20000));
        at("2027-01-02T03:00:00Z");balance(closed("2026-12"),-9000,9000,0);
        List<Map<String,Object>> ledger=jdbc.queryForList("SELECT * FROM settlement_seller_carryover ORDER BY id");
        Reply earlier=closeMonth("2026-11");
        assertThat(earlier.status()).isEqualTo(409);
        assertThat(earlier.body().path("error").path("message").asText()).isEqualTo("이후 월이 이미 마감됐습니다.");
        assertThat(earlier.body().path("error").path("code").asText()).isEqualTo(closeMonth("2027-01").body().path("error").path("code").asText());
        assertThat(monthRows("2026-11")).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM settlement_seller_carryover ORDER BY id")).isEqualTo(ledger);
        assertThat(closed("2026-12").path("carriedIn").asLong()).as("이미 CLOSED인 대상 월 재요청은 첫 결과(R3.4)").isEqualTo(-9000);
    }
    /** [D-046] 추가 방어선: 이월 원장은 판매자마다 같은 출처 월을 한 번만 받는다. 다른 판매자는 같은 출처 월을 쓸 수 있다. */
    @Test @Tag("known-defect") void theCarryLedgerAcceptsEachSourceMonthOncePerSeller() {
        String insert="INSERT INTO settlement_seller_carryover (seller_subject,settlement_month,net_seller_payout_amount,carried_in,payable,carried_out,source_month,created_at)"
                +" VALUES (?,?,0,0,0,0,?,'2027-01-02 03:00:00')";
        jdbc.update(insert,"usr_seller","2026-10","2026-09");
        assertThatThrownBy(() -> jdbc.update(insert,"usr_seller","2026-11","2026-09"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_seller_carryover_source");
        jdbc.update(insert,"usr_other","2026-10","2026-09");
        jdbc.update(insert,"usr_seller","2026-08",null);jdbc.update(insert,"usr_seller","2026-07",null);
        assertThat(count("settlement_seller_carryover")).isEqualTo(4);
    }
    /** [D-045] 10월 마감이 판매자를 잠근 동안 도착한 9/30 환불은 마감을 기다렸다가 11월로 넘어간다. */
    @Test @Tag("known-defect") void aLateEarlierMonthRefundWaitsForTheLaterCloseAndMovesToTheCurrentMonth() throws Exception {
        PaidOrder august=augustClosedAndOctoberSold();
        at("2026-11-03T03:00:00Z");ConfirmedRefund late=refundConfirmedAt(august,SEPTEMBER_30_KST);
        Gate locked=PAUSES.arm(MONTH_LOCKED,"close");
        Run<SellerMonthlySettlement> closing=PausePoints.start("close",() -> close.execute("usr_seller","2026-10"));
        locked.awaitArrival();
        Run<SettlementRefund> refunding=PausePoints.start("refund",() -> recordRefund.execute(late));
        assertThat(refunding.stillRunningAfter(BLOCKED)).as("마감이 판매자를 잠근 동안 앞 월 환불은 기다린다").isTrue();
        locked.release();
        assertThat(closing.join().isClosed()).isTrue();
        SettlementRefund refund=refunding.join();
        assertThat(List.of(refund.getSettlementMonth(),refund.getMonthReason())).containsExactly("2026-11","CLOSED_MONTH_ROLLOVER");
        assertThat(monthRows("2026-09")).isZero();
    }
    /** [D-045] 앞 월 매출이 판매자를 잠근 동안 10월 마감은 기다리고, 새로 생긴 9월 OPEN 때문에 409로 끝난다. 9월부터 마감하면 이월이 이어진다. */
    @Test @Tag("known-defect") void aLaterCloseWaitsForAnEarlierMonthSaleAndThenRequiresThatMonthFirst() throws Exception {
        augustClosedAndOctoberSold();
        at("2026-11-03T03:00:00Z");PaidOrder late=paid(SEPTEMBER_30_KST,5000);prepare(late);
        Gate locked=PAUSES.arm(MONTH_LOCKED,"sale");
        Run<SettlementSale> recording=PausePoints.start("sale",() -> record.execute(late));
        locked.awaitArrival();
        Run<SellerMonthlySettlement> closing=PausePoints.start("close",() -> close.execute("usr_seller","2026-10"));
        assertThat(closing.stillRunningAfter(BLOCKED)).as("앞 월 매출이 판매자를 잠근 동안 뒤 월 마감은 기다린다").isTrue();
        locked.release();
        assertThat(recording.join().getSettlementMonth()).isEqualTo("2026-09");
        assertThat(closing.failure()).isInstanceOf(BusinessException.class).hasMessage("이전 월을 먼저 마감해야 합니다.");
        assertThat(monthStatus("2026-10")).isEqualTo("OPEN");
        balance(closed("2026-09"),0,4500,0);
        balance(closed("2026-10"),0,4500,0);
        assertThat(carryover("2026-10").get("source_month")).isEqualTo("2026-09");
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
    @Test void listenerConsumerGroupsMatchTheInboxKeysUsedByTheirServices() throws Exception {
        PaidOrder input = order();
        prepare(input);
        OrderPaidEvent paid = new OrderPaidEvent(input.eventId(), input.occurredAt(), input.orderNo(),
                input.requestEventId(), input.paymentResultEventId(), input.paymentId(), input.buyerSubject(),
                input.productId(), input.productCode(), input.title(), input.contentVersion(), input.amount(), input.currency());
        ProducerRecord<String, String> message = new ProducerRecord<>(Topics.ORDER, input.orderNo(), mapper.writeValueAsString(paid));
        message.headers().add(EventHeaders.EVENT_ID, paid.eventId().getBytes(StandardCharsets.UTF_8));
        message.headers().add(EventHeaders.EVENT_TYPE, paid.eventType().getBytes(StandardCharsets.UTF_8));
        listeners.start();
        try {
            kafka.send(message).get(10, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("settlement_sale")).isEqualTo(1));
            assertInboxUsesListenerGroup(Topics.ORDER, paid.eventId());

            RefundPaymentConfirmedEvent confirmed = event(prepareRefund(input));
            sendRefundEvent(confirmed.eventId(), confirmed.eventType(), input.orderNo(), confirmed);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("settlement_refund")).isEqualTo(1));
            assertInboxUsesListenerGroup(Topics.PAYMENT, confirmed.eventId());
            assertThat(count("processed_event")).isEqualTo(2);
        } finally {
            listeners.stop();
        }
    }

    private void assertInboxUsesListenerGroup(String topic, String eventId) {
        List<String> groups = listeners.getListenerContainers().stream()
                .filter(container -> container.getContainerProperties().getTopics() != null)
                .filter(container -> Arrays.asList(container.getContainerProperties().getTopics()).contains(topic))
                .map(container -> container.getGroupId()).toList();
        String inboxGroup = jdbc.queryForObject("SELECT consumer_group FROM processed_event WHERE event_id=?", String.class, eventId);
        assertThat(groups).as("%s listener group must match the Inbox group for %s", topic, eventId).containsExactly(inboxGroup);
    }

    @Test void refundBeforeOriginalSaleGoesToDltAndOriginalReplayPostsOnceDespiteLateExecutionEnrichment() throws Exception {
        PaidOrder input=order(); prepare(input); ConfirmedRefund confirmation=prepareRefund(input);
        RefundPaymentConfirmedEvent financial=event(confirmation);
        Map<String,Object> configuration=new HashMap<>();
        configuration.put("bootstrap.servers",kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
        configuration.put("group.id","settlement-refund-dlt-"+id()); configuration.put("auto.offset.reset","earliest");
        configuration.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        configuration.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        try(KafkaConsumer<String,String> consumer=new KafkaConsumer<>(configuration);
                AdminClient admin=AdminClient.create(Map.of("bootstrap.servers",configuration.get("bootstrap.servers")))) {
            consumer.subscribe(List.of(Topics.PAYMENT+".DLT")); listeners.start(); sendRefundEvent(financial.eventId(),financial.eventType(),input.orderNo(),financial);
            boolean found=false; long deadline=System.nanoTime()+Duration.ofSeconds(35).toNanos();
            while(!found && System.nanoTime()<deadline) for(ConsumerRecord<String,String> failure:consumer.poll(Duration.ofMillis(300)))
                if(failure.value().contains(financial.eventId())) found=true;
            assertThat(found).isTrue(); assertThat(count("settlement_refund")).isZero(); assertThat(count("processed_event")).isZero();
            record.execute(input);
            sendRefundEvent(financial.eventId(),financial.eventType(),input.orderNo(),financial);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count("settlement_refund")).isEqualTo(1));
            sendRefundEvent(financial.eventId(),financial.eventType(),input.orderNo(),financial);
            RefundExecutionSucceededEvent late=new RefundExecutionSucceededEvent(id(),NOW,financial.eventId(),financial.refundId(),financial.attemptId(),
                    financial.requestEventId(),financial.orderNo(),financial.paymentId(),financial.buyerSubject(),financial.amount(),financial.currency(),
                    financial.providerTransactionId(),financial.cancellationIds().getFirst());
            RecordMetadata last=sendRefundEvent(late.eventId(),late.eventType(),input.orderNo(),late);
            TopicPartition partition=new TopicPartition(last.topic(),last.partition());
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(admin.listConsumerGroupOffsets(RecordConfirmedRefund.CONSUMER_GROUP).partitionsToOffsetAndMetadata()
                            .get(5,TimeUnit.SECONDS).get(partition).offset()).isGreaterThan(last.offset()));
            assertThat(count("settlement_refund")).isEqualTo(1); assertThat(count("processed_event")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT refund_count FROM seller_monthly_settlement",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT seller_payout_amount-refunded_seller_payout_amount FROM seller_monthly_settlement",Long.class)).isZero();
        } finally { listeners.stop(); }
    }
    RecordMetadata sendRefundEvent(String eventId,String eventType,String orderNo,Object body) throws Exception {
        ProducerRecord<String,String> message=new ProducerRecord<>(Topics.PAYMENT,orderNo,mapper.writeValueAsString(body));
        message.headers().add(EventHeaders.EVENT_ID,eventId.getBytes(StandardCharsets.UTF_8));
        message.headers().add(EventHeaders.EVENT_TYPE,eventType.getBytes(StandardCharsets.UTF_8));
        return kafka.send(message).get(10,TimeUnit.SECONDS).getRecordMetadata();
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
    @Test void disabledStatementSourceReturnsUnavailableRatherThanAnEmptyFinancialReport() {
        Reply result=http("POST","/api/v1/settlements/pg-statements",token("usr_admin","ADMIN"),Map.of("dateType","DUE","baseDate","2026-10-01"));
        assertThat(result.status()).isEqualTo(503);assertThat(count("settlement_pg_statement")).isZero();
    }
    @Test void publicOpenApiHasTwentyOneOperationsAndNoLegacyOrInternalRoutes() throws Exception {
        OpenApiSnapshot.verify(port,"settlement");
        JsonNode paths=http("GET","/v3/api-docs",null,null).body().path("paths");
        assertThat(paths.size()).isEqualTo(19);int operations=0;for(JsonNode path:paths) operations+=path.size();assertThat(operations).isEqualTo(21);
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
