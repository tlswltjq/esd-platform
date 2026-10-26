package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.LicenseSuspensionRequestedEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.order.config.RefundConfiguration;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.OrderLicenseGrantRepository;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.service.RequestRefund;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InfraContainers.MySql.class, RefundConfiguration.class, RefundReceptionIntegrationTest.OutboxConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundReceptionIntegrationTest {
    private static final Instant PAYMENT_CONFIRMED_AT = Instant.parse("2026-10-25T10:00:00.123456Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-25T13:00:00.123456Z");

    @Autowired RequestRefund request;
    @Autowired ApplyLicenseGrant applyGrant;
    @Autowired OrderLicenseGrantRepository grants;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;

    @TestConfiguration(proxyBeanMethods = false)
    static class OutboxConfiguration {
        @Bean
        ProcessedEventGuard inboxGuard(ProcessedEventRepository repository) {
            return new ProcessedEventGuard(repository);
        }

        @Bean
        OutboxRecorder outboxRecorder(OutboxEventRepository repository) {
            return new OutboxRecorder(repository, new ObjectMapper().registerModule(new JavaTimeModule()));
        }
    }

    @BeforeEach
    void initialize() {
        clearDatabase();
        when(clock.instant()).thenReturn(RECEIVED_AT);
    }

    @AfterEach
    void clearDatabase() {
        jdbc.update("DELETE FROM order_refund_attempt");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    void anEligibleRequestCommitsBothRefundAndMatchingOutbox() throws Exception {
        Order paid = grantedOrder();
        PaymentResult originalPayment = paid.paymentResult();

        Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        Refund stored = refunds.findByOrderNo(paid.getOrderNo()).orElseThrow();
        assertThat(stored.getId()).isNotNull();
        assertThat(stored.getRefundId()).isEqualTo(received.getRefundId());
        assertThat(stored.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(stored.isAutomaticRefundEligible()).isTrue();
        assertThat(stored.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
        Map<String, Object> metadata = jdbc.queryForMap(
                "SELECT event_id, event_type, aggregate_type, aggregate_id, topic, partition_key "
                        + "FROM outbox_event WHERE partition_key=?", paid.getOrderNo());
        assertThat(metadata).containsEntry("event_type", "LicenseSuspensionRequested")
                .containsEntry("aggregate_type", "Order")
                .containsEntry("aggregate_id", paid.getOrderNo())
                .containsEntry("topic", "stove.order.v1")
                .containsEntry("partition_key", paid.getOrderNo());
        String json = jdbc.queryForObject("SELECT payload FROM outbox_event WHERE partition_key=?",
                String.class, paid.getOrderNo());
        LicenseSuspensionRequestedEvent event = new ObjectMapper().registerModule(new JavaTimeModule())
                .readValue(json, LicenseSuspensionRequestedEvent.class);
        assertThat(event.eventId()).isNotBlank().isEqualTo(metadata.get("event_id"));
        assertThat(event.eventId()).isEqualTo(stored.getSuspensionRequestEventId());
        assertThat(event.occurredAt()).isEqualTo(RECEIVED_AT);
        assertThat(event.refundId()).isEqualTo(stored.getRefundId());
        assertThat(event.orderNo()).isEqualTo(paid.getOrderNo());
        assertThat(event.buyerSubject()).isEqualTo(paid.getBuyerSubject());
        assertThat(event.productId()).isEqualTo(paid.getProductId());
        assertThat(event.productCode()).isEqualTo(paid.getProductCode());
        assertThat(event.paymentId()).isEqualTo(originalPayment.paymentId());
        assertThat(orders.findById(paid.getId()).orElseThrow().paymentResult()).isEqualTo(originalPayment);
    }

    @Test
    void aLateRequestCommitsReviewWithoutAnySuspensionOutbox() {
        Order paid = grantedOrder();
        Instant lateReceipt = PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(4)).plusNanos(1_000);
        when(clock.instant()).thenReturn(lateReceipt);

        Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        Refund stored = refunds.findByOrderNo(paid.getOrderNo()).orElseThrow();
        assertThat(stored.getRefundId()).isEqualTo(received.getRefundId());
        assertThat(stored.getReceivedAt()).isEqualTo(lateReceipt);
        assertThat(stored.isAutomaticRefundEligible()).isFalse();
        assertThat(stored.getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(outboxCount(paid.getOrderNo())).isZero();
    }

    @Test
    void aRepeatedRequestAfterTheDeadlinePreservesTheCommittedRefundAndOutbox() {
        Order paid = grantedOrder();
        Refund first = request.execute(paid.getBuyerSubject(), paid.getOrderNo());
        when(clock.instant()).thenReturn(PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(6)));

        Refund repeated = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        assertThat(repeated.getRefundId()).isEqualTo(first.getRefundId());
        assertThat(repeated.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(repeated.isAutomaticRefundEligible()).isTrue();
        assertThat(refunds.count()).isEqualTo(1);
        assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
    }

    @Test
    void outboxFailureAfterInsertionRollsBackBothRowsAndAllowsANewReception() {
        Order paid = grantedOrder();
        PaymentResult originalPayment = paid.paymentResult();
        try {
            // MANDATORY 프록시를 통과하는 spy 설정에도 트랜잭션이 필요하다.
            new TransactionTemplate(transactions).executeWithoutResult(transaction ->
                    doAnswer(call -> {
                        call.callRealMethod();
                        throw new IllegalStateException("abort after outbox insert");
                    }).when(outbox).record(eq("Order"), eq(paid.getOrderNo()), any(DomainEvent.class)));

            assertThatThrownBy(() -> request.execute(paid.getBuyerSubject(), paid.getOrderNo()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("abort after outbox insert");

            assertThat(refunds.count()).isZero();
            assertThat(outboxCount(paid.getOrderNo())).isZero();
            assertThat(orders.findById(paid.getId()).orElseThrow().paymentResult()).isEqualTo(originalPayment);
        } finally {
            reset(outbox);
        }
        Refund retried = request.execute(paid.getBuyerSubject(), paid.getOrderNo());
        assertThat(refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getRefundId())
                .isEqualTo(retried.getRefundId());
        assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
    }

    @Test
    void simultaneousRequestsReturnTheSameRefundAndRecordOneSuspensionRequest() throws Exception {
        Order paid = grantedOrder();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate firstTransaction = new TransactionTemplate(transactions);
        firstTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        when(clock.instant()).thenReturn(RECEIVED_AT, PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(6)));
        try {
            Future<Refund> first = pool.submit(() -> firstTransaction.execute(transaction -> {
                Refund received = request.execute(paid.getBuyerSubject(), paid.getOrderNo());
                inserted.countDown();
                await(commit);
                return received;
            }));
            await(inserted);
            Future<Refund> second = pool.submit(() -> {
                contenderStarted.countDown();
                return request.execute(paid.getBuyerSubject(), paid.getOrderNo());
            });
            await(contenderStarted);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            Refund firstResult = first.get(10, TimeUnit.SECONDS);
            Refund secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(secondResult.getRefundId()).isEqualTo(firstResult.getRefundId());
            assertThat(secondResult.getReceivedAt()).isEqualTo(RECEIVED_AT);
            assertThat(secondResult.isAutomaticRefundEligible()).isTrue();
            assertThat(refunds.count()).isEqualTo(1);
            assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
        } finally {
            commit.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void theDatabaseRejectsASecondRefundEvenWhenTheServiceLockIsBypassed() {
        Order paid = grantedOrder();
        Refund first = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        assertThatThrownBy(() -> refunds.saveAndFlush(Refund.request(paid, RECEIVED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("uk_order_refund_order");

        assertThat(refunds.count()).isEqualTo(1);
        assertThat(refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getRefundId()).isEqualTo(first.getRefundId());
        assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
    }

    @Test
    void anotherBuyerCannotReceiveTheExistingRefund() {
        Order paid = grantedOrder();
        Refund first = request.execute(paid.getBuyerSubject(), paid.getOrderNo());

        assertThatThrownBy(() -> request.execute("usr_other", paid.getOrderNo()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        assertThat(refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getRefundId()).isEqualTo(first.getRefundId());
        assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "FAILED"})
    void anUnpaidOrderLeavesNoRefundOrOutbox(OrderStatus status) {
        Order unpaid = savedOrder(status);

        assertThatThrownBy(() -> request.execute(unpaid.getBuyerSubject(), unpaid.getOrderNo()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        assertThat(refunds.count()).isZero();
        assertThat(outboxCount(unpaid.getOrderNo())).isZero();
        assertThat(orders.findById(unpaid.getId()).orElseThrow().getStatus()).isEqualTo(status);
    }

    private Order grantedOrder() {
        Order paid = savedOrder(OrderStatus.PAID);
        applyGrant.execute(grant(paid));
        return paid;
    }

    @Test
    void aRefundWaitsAndResumesOnlyOnceWhenGrantConfirmationArrivesLate() {
        Order paid = savedOrder(OrderStatus.PAID);
        Refund first = request.execute(paid.getBuyerSubject(), paid.getOrderNo());
        assertThat(first.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        assertThat(outboxCount(paid.getOrderNo())).isZero();
        LicenseGrant input = grant(paid);
        when(clock.instant()).thenReturn(PAYMENT_CONFIRMED_AT.plus(Duration.ofHours(6)));

        applyGrant.execute(input);
        applyGrant.execute(input);

        Refund stored = refunds.findByOrderNo(paid.getOrderNo()).orElseThrow();
        assertThat(stored.getRefundId()).isEqualTo(first.getRefundId());
        assertThat(stored.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(stored.isAutomaticRefundEligible()).isTrue();
        assertThat(stored.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(grants.findByOrderNo(paid.getOrderNo()).orElseThrow().confirmation()).isEqualTo(input);
        assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
    }

    @Test
    void aFailureWhileResumingRollsBackGrantInboxStatusAndOutboxTogether() {
        Order paid = savedOrder(OrderStatus.PAID);
        request.execute(paid.getBuyerSubject(), paid.getOrderNo());
        LicenseGrant input = grant(paid);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction ->
                    doAnswer(call -> {
                        call.callRealMethod();
                        throw new IllegalStateException("abort resume after outbox insert");
                    }).when(outbox).record(eq("Order"), eq(paid.getOrderNo()), any(DomainEvent.class)));
            assertThatThrownBy(() -> applyGrant.execute(input)).isInstanceOf(IllegalStateException.class);
            assertThat(grants.count()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class)).isZero();
            assertThat(outboxCount(paid.getOrderNo())).isZero();
            assertThat(refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.PENDING_LICENSE_GRANT);
        } finally {
            reset(outbox);
        }
        applyGrant.execute(input);
        assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void grantConfirmationAndReceptionSerializeForEitherFirstWriter(boolean grantFirst) throws Exception {
        Order paid = savedOrder(OrderStatus.PAID);
        LicenseGrant input = grant(paid);
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            Future<?> first = pool.submit(() -> transaction.executeWithoutResult(status -> {
                if (grantFirst) applyGrant.execute(input);
                else request.execute(paid.getBuyerSubject(), paid.getOrderNo());
                firstWritten.countDown();
                await(commit);
            }));
            await(firstWritten);
            Future<?> second = pool.submit(() -> {
                contenderStarted.countDown();
                if (grantFirst) request.execute(paid.getBuyerSubject(), paid.getOrderNo());
                else applyGrant.execute(input);
            });
            await(contenderStarted);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(refunds.findByOrderNo(paid.getOrderNo()).orElseThrow().getStatus())
                    .isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
            assertThat(grants.count()).isEqualTo(1);
            assertThat(refunds.count()).isEqualTo(1);
            assertThat(outboxCount(paid.getOrderNo())).isEqualTo(1);
        } finally {
            commit.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static LicenseGrant grant(Order paid) {
        return new LicenseGrant(id(), PAYMENT_CONFIRMED_AT.plusSeconds(60), id(), paid.getOrderNo(),
                paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
    }

    private Order savedOrder(OrderStatus status) {
        Order order = order();
        ReflectionTestUtils.setField(order, "createdAt", PAYMENT_CONFIRMED_AT.minus(Duration.ofHours(2)));
        if (status != OrderStatus.PENDING) {
            order.apply(new PaymentResult(id(), PAYMENT_CONFIRMED_AT, order.getOrderNo(),
                    order.getRequestEventId(), id(), order.getBuyerSubject(), order.getAmount(),
                    order.getCurrency(), status, status == OrderStatus.FAILED ? "DECLINED" : null,
                    status == OrderStatus.FAILED ? "승인이 거절되었습니다." : null));
        }
        return orders.saveAndFlush(order);
    }

    private int outboxCount(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE partition_key=?", Integer.class, orderNo);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
