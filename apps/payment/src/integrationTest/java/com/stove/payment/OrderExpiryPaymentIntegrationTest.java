package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PaymentFixtures.approved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.OrderExpiredEvent;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.adapter.pg.SimulatedPgHttpGateway;
import com.stove.payment.core.domain.OrderExpiryInput;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentOrderRequest;
import com.stove.payment.core.domain.PaymentOrderLifecycle;
import com.stove.payment.core.domain.PaymentOrderLifecycleRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.service.ApplyOrderExpiry;
import com.stove.payment.core.port.OrderPaymentLifecycleLocks;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = {"stove.payment.pg.base-url=http://127.0.0.1:1", "stove.payment.pg.api-key=unused-expiry-fixture"})
class OrderExpiryPaymentIntegrationTest extends PaymentDatabaseIntegrationTestBase {
    @MockitoBean(enforceOverride = true) SimulatedPgHttpGateway gateway;
    @Autowired ReceiveOrderPayment receive;
    @Autowired ApplyOrderExpiry expire;
    @Autowired OrderPaymentLifecycleLocks locks;
    @Autowired ResolvePayment resolve;
    @Autowired PaymentRepository payments;
    @Autowired PaymentOrderLifecycleRepository lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;

    @BeforeEach @AfterEach void clean() {
        listeners.stop();
        verifyNoInteractions(gateway);
        jdbc.update("DELETE FROM refund_execution"); jdbc.update("DELETE FROM payment_pg_webhook");
        jdbc.update("DELETE FROM purchase_payment"); jdbc.update("DELETE FROM payment_order_lifecycle");
        jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
    }
    @Test void storageLocksCannotEscapeTheServiceTransaction() {
        PaymentOrderRequest source=request();
        assertThatThrownBy(()->locks.ensure(source.orderNo(),source.eventId(),source.buyerSubject(),source.amount(),source.currency(),source.occurredAt().plusSeconds(1800)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lifecycle.count()).isZero();
    }
    @Test void anIdlePaymentExpiresAndStopsBothLookupSchedulesWithoutPublishingAFinancialFailure() {
        PaymentOrderRequest source=request(); Payment payment=receive.execute(source); OrderExpiryInput input=expiry(source);
        expire.execute(input); Payment stored=stored(payment);
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.EXPIRED); assertThat(stored.getNextAttemptAt()).isNull();
        assertThat(stored.getNextFailureLookupAt()).isNull(); assertThat(stored.getResultEventId()).isNull();
        assertThat(stored.supplierResult()).isNull(); assertThat(count("outbox_event")).isZero(); resolve.execute(payment.getPaymentId());
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }
    @Test void expiryBeforeCreationIsRetainedAndLateOrderPlacedNeverOpensACheckoutOrPoll() {
        PaymentOrderRequest source=request(); expire.execute(expiry(source)); assertThat(payments.count()).isZero();
        Payment created=receive.execute(source); assertThat(created.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(receive.execute(source).getPaymentId()).isEqualTo(created.getPaymentId()); assertThat(created.getNextAttemptAt()).isNull();
        assertThat(count("processed_event")).isEqualTo(2); assertThat(count("outbox_event")).isZero();
    }
    @Test void duplicateDeliveryKeepsTheOriginalExpiryAndChangedContentIsRejectedBeforeDeduplication() {
        PaymentOrderRequest source=request(); Payment payment=receive.execute(source); OrderExpiryInput input=expiry(source);
        expire.execute(input); Payment before=stored(payment); expire.execute(input);
        assertThat(stored(payment).getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThatThrownBy(()->expire.execute(new OrderExpiryInput(input.eventId(),input.occurredAt().plusSeconds(1),input.orderNo(),input.requestEventId(),input.buyerSubject(),input.amount(),input.currency(),input.expiresAt()))).isInstanceOf(BusinessException.class);
        assertThat(count("processed_event")).isEqualTo(2); assertThat(lifecycle.findById(source.orderNo()).orElseThrow().getExpiredAt()).isEqualTo(input.occurredAt());
    }
    @Test void anotherBuyerAmountOrOriginalSourceCannotExpireTheOwnedPurchase() {
        PaymentOrderRequest source=request(); Payment payment=receive.execute(source); OrderExpiryInput input=expiry(source);
        for(OrderExpiryInput invalid:List.of(
                new OrderExpiryInput(input.eventId(),input.occurredAt(),input.orderNo(),id(),input.buyerSubject(),input.amount(),input.currency(),input.expiresAt()),
                new OrderExpiryInput(input.eventId(),input.occurredAt(),input.orderNo(),input.requestEventId(),"other",input.amount(),input.currency(),input.expiresAt()),
                new OrderExpiryInput(input.eventId(),input.occurredAt(),input.orderNo(),input.requestEventId(),input.buyerSubject(),input.amount()+1,input.currency(),input.expiresAt())))
            assertThatThrownBy(()->expire.execute(invalid)).isInstanceOf(BusinessException.class);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(count("processed_event")).isEqualTo(1);
    }
    @Test void anActualFlushedExpiryRollsBackWithTheInboxAndParentState() {
        PaymentOrderRequest source=request(); Payment payment=receive.execute(source); OrderExpiryInput input=expiry(source);
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            expire.execute(input); entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_payment WHERE payment_id=?",String.class,payment.getPaymentId())).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=?",Integer.class,input.eventId())).isEqualTo(1);
            throw new IllegalStateException("after real expiry, parent and Inbox flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(lifecycle.findById(source.orderNo()).orElseThrow().expired()).isFalse();
        expire.execute(input); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }
    @Test void aCommittedFinancialResultIsRetainedWithTheConflictingOrderNotice() {
        PaymentOrderRequest source=request(); Payment payment=receive.execute(source);
        new TransactionTemplate(transactions).executeWithoutResult(tx->payments.lockByPaymentId(payment.getPaymentId()).orElseThrow().confirm(approved(payment),id(),Payment.now()));
        Payment paid=stored(payment); expire.execute(expiry(source));
        assertThat(stored(payment).supplierResult()).isEqualTo(paid.supplierResult()); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
        PaymentOrderLifecycle receipt=lifecycle.findById(source.orderNo()).orElseThrow(); assertThat(receipt.expired()).isTrue(); assertThat(receipt.isFinancialConflict()).isTrue();
        assertThat(count("outbox_event")).isZero();
    }
    @Test void simultaneousCreationAndExpiryConvergeOnOneExpiredPayment() throws Exception {
        PaymentOrderRequest source=request(); OrderExpiryInput input=expiry(source);
        together(()->{receive.execute(source);return true;},()->{expire.execute(input);return true;});
        assertThat(payments.count()).isEqualTo(1); assertThat(payments.findByOrderNo(source.orderNo()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(count("processed_event")).isEqualTo(2);
    }
    @Test void concurrentDuplicateNoticesHaveOneReceiptAndOneInboxMarker() throws Exception {
        PaymentOrderRequest source=request(); receive.execute(source); OrderExpiryInput input=expiry(source);
        together(()->{expire.execute(input);return true;},()->{expire.execute(input);return true;});
        assertThat(count("processed_event")).isEqualTo(2); assertThat(lifecycle.count()).isEqualTo(1);
    }
    @Test void expiryCannotBeReassignedToAnotherOrderWithTheSameEventId() {
        PaymentOrderRequest first=request(); OrderExpiryInput event=expiry(first); expire.execute(event);
        PaymentOrderRequest second=request(); Payment other=receive.execute(second); OrderExpiryInput duplicate=expiry(second);
        assertThatThrownBy(()->expire.execute(new OrderExpiryInput(event.eventId(),duplicate.occurredAt(),duplicate.orderNo(),duplicate.requestEventId(),duplicate.buyerSubject(),duplicate.amount(),duplicate.currency(),duplicate.expiresAt()))).isInstanceOf(BusinessException.class);
        assertThat(stored(other).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
    @Test void theProductionKafkaListenerPersistsAnEarlyNoticeAndIgnoresItsRedelivery() throws Exception {
        PaymentOrderRequest source=request(); OrderExpiryInput input=expiry(source);
        OrderExpiredEvent event=new OrderExpiredEvent(input.eventId(),input.occurredAt(),input.orderNo(),input.requestEventId(),input.buyerSubject(),input.amount(),input.currency(),input.expiresAt());
        for(MessageListenerContainer container:listeners.getListenerContainers())if(ApplyOrderExpiry.CONSUMER_GROUP.equals(container.getGroupId()))container.start();
        try {
            send(event); send(event);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(lifecycle.findById(source.orderNo())).hasValueSatisfying(receipt->assertThat(receipt.expired()).isTrue()));
            assertThat(receive.execute(source).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=? AND consumer_group=?",Integer.class,input.eventId(),ApplyOrderExpiry.CONSUMER_GROUP)).isEqualTo(1);
        } finally { listeners.stop(); }
    }
    private void send(OrderExpiredEvent event) throws Exception {
        ProducerRecord<String,String> record=new ProducerRecord<>(event.topic(),event.partitionKey(),mapper.writeValueAsString(event));
        record.headers().add(EventHeaders.EVENT_ID,event.eventId().getBytes(StandardCharsets.UTF_8)); record.headers().add(EventHeaders.EVENT_TYPE,event.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10,TimeUnit.SECONDS);
    }
    private <T> void together(Callable<T> first,Callable<T> second) throws Exception {
        CyclicBarrier barrier=new CyclicBarrier(2);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            Future<T> one=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return first.call();});
            Future<T> two=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return second.call();});
            one.get(20,TimeUnit.SECONDS); two.get(20,TimeUnit.SECONDS);
        }
    }
    private Payment stored(Payment payment) { return payments.findById(payment.getId()).orElseThrow(); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private OrderExpiryInput expiry(PaymentOrderRequest source) { return new OrderExpiryInput(id(),source.occurredAt().plusSeconds(1801),source.orderNo(),source.eventId(),source.buyerSubject(),source.amount(),source.currency(),source.occurredAt().plusSeconds(1800)); }
}
