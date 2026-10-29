package com.stove.payment;

import static com.stove.payment.PaymentFixtures.approved;
import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.RefundFixtures.execution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.stove.common.testcontainers.SharedContainers;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecution;
import com.stove.payment.core.domain.RefundExecutionRepository;
import com.stove.payment.core.domain.RefundExecutionRequest;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ReceiveRefundExecution;
import com.stove.payment.core.service.RecordRefundCancellationPreparation;
import com.stove.payment.core.service.RecordRefundPreparation;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

class RefundCancellationWindowIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant BASE = Payment.now();
    @Autowired ReceiveOrderPayment orderReceipt;
    @Autowired ReceiveRefundExecution receive;
    @Autowired RecordRefundCancellationPreparation prepare;
    @Autowired RecordRefundPreparation preparationEvents;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoSpyBean RefundExecutionRepository executions;

    @BeforeEach void initialize() { clear(); when(clock.instant()).thenReturn(BASE); }

    @AfterEach void clear() {
        reset(executions);
        jdbc.update("DELETE FROM payment_pg_webhook");
        jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void reloadAndRestartKeepTheFirstDeadlineAndRequireLookupAfterThreeHours() {
        RefundExecution received = received();
        RefundExecutionRequest original = received.request();
        Instant first = BASE.plusSeconds(86400);
        when(clock.instant()).thenReturn(first);
        RefundCancellationPermission initial = prepare.execute(received.getAttemptId());
        assertThat(initial.firstPreparedAt()).isEqualTo(first);
        assertThat(stored(received).getFirstCancellationPreparedAt()).isEqualTo(first);
        assertThat(prepare.execute(received.getAttemptId())).isEqualTo(initial);

        Instant deadline = first.plusSeconds(10800);
        RecordRefundCancellationPreparation restarted = new RecordRefundCancellationPreparation(executions, transactions,
                Clock.fixed(deadline, ZoneOffset.UTC), preparationEvents);
        RefundCancellationPermission expired = restarted.execute(received.getAttemptId());
        assertThat(expired.maySendAt(deadline)).isFalse();
        assertThat(expired.requiresOperatorReviewAt(deadline)).isTrue();
        assertThat(expired.request()).isEqualTo(original);
        assertThat(stored(received).getAutomaticCancellationStoppedAt()).isEqualTo(deadline);
        assertThat(stored(received).getStatus()).isEqualTo(RefundExecutionStatus.PENDING);

        when(clock.instant()).thenReturn(deadline.plusSeconds(3600));
        assertThat(receive.execute(original).request()).isEqualTo(original);
        assertThat(prepare.execute(received.getAttemptId())).isEqualTo(expired);
        assertThat(stored(received).getAutomaticCancellationStoppedAt()).isEqualTo(deadline);
        assertThat(payments.findByPaymentId(received.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionPrepared'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type<>'RefundExecutionPrepared'", Integer.class)).isZero();
    }

    @Test void concurrentPreparationsCannotMoveTheFirstTimeToTheLaterWorker() throws Exception {
        RefundExecution received = received();
        AtomicLong seconds = new AtomicLong(1);
        when(clock.instant()).thenAnswer(call -> BASE.plusSeconds(seconds.getAndIncrement()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<RefundCancellationPermission>> futures = List.of(
                    pool.submit(() -> { start.await(); return prepare.execute(received.getAttemptId()); }),
                    pool.submit(() -> { start.await(); return prepare.execute(received.getAttemptId()); }));
            start.countDown();
            RefundCancellationPermission first = futures.get(0).get(15, TimeUnit.SECONDS);
            RefundCancellationPermission second = futures.get(1).get(15, TimeUnit.SECONDS);
            assertThat(first).isEqualTo(second);
            assertThat(first.firstPreparedAt()).isEqualTo(BASE.plusSeconds(1));
            assertThat(stored(received).getFirstCancellationPreparedAt()).isEqualTo(BASE.plusSeconds(1));
            assertThat(executions.count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void aFailureAfterFlushRollsBackPreparationBeforeAnyPermissionCanEscape() {
        RefundExecution received = received();
        when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        doAnswer(call -> { entityManager.flush(); throw new TransientDataAccessResourceException("fixture after flush"); })
                .when(executions).flush();
        assertThatThrownBy(() -> prepare.execute(received.getAttemptId())).isInstanceOf(TransientDataAccessResourceException.class);
        assertThat(stored(received).getFirstCancellationPreparedAt()).isNull();
        assertThat(stored(received).getAutomaticCancellationStoppedAt()).isNull();
        reset(executions);
        when(clock.instant()).thenReturn(BASE.plusSeconds(30));
        assertThat(prepare.execute(received.getAttemptId()).firstPreparedAt()).isEqualTo(BASE.plusSeconds(30));
    }

    @Test void databaseConstraintsRejectAStopWithoutPreparationOrBeforeTheDeadline() {
        RefundExecution received = received();
        assertCancellationStopConstraint(() -> jdbc.update("UPDATE refund_execution SET auto_cancel_stopped_at=received_at WHERE attempt_id=?",
                received.getAttemptId()));
        prepare.execute(received.getAttemptId());
        assertCancellationStopConstraint(() -> jdbc.update("UPDATE refund_execution SET auto_cancel_stopped_at=TIMESTAMPADD(SECOND,10799,first_cancel_prepared_at) WHERE attempt_id=?",
                received.getAttemptId()));
        assertThat(stored(received).getAutomaticCancellationStoppedAt()).isNull();
    }

    @Test void migrationFromV15PreservesAnUnsentPendingReceiptWithoutInventingADispatchTime() {
        RefundExecution received = received();
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        String schema = "refund_window_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            JdbcTemplate legacy = new JdbcTemplate(source);
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("15").load().migrate();
            String paymentColumns = String.join(",", legacy.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='purchase_payment' AND GENERATION_EXPRESSION='' ORDER BY ORDINAL_POSITION", String.class, schema));
            legacy.update("INSERT INTO purchase_payment(" + paymentColumns + ") SELECT " + paymentColumns + " FROM " + mysql.getDatabaseName() + ".purchase_payment WHERE payment_id=?", received.getPaymentId());
            String columns = "attempt_id,refund_id,request_event_id,request_occurred_at,order_no,payment_id,buyer_subject,amount,currency,license_suspension_event_id,received_at,status";
            legacy.update("INSERT INTO refund_execution(" + columns + ") SELECT " + columns + " FROM " + mysql.getDatabaseName()
                    + ".refund_execution WHERE attempt_id=?", received.getAttemptId());
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM refund_execution WHERE attempt_id=? AND status='PENDING' AND first_cancel_prepared_at IS NULL AND auto_cancel_stopped_at IS NULL",
                    Integer.class, received.getAttemptId())).isEqualTo(1);
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    private RefundExecution received() {
        Payment payment = orderReceipt.execute(request());
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                payments.lockByPaymentId(payment.getPaymentId()).orElseThrow().confirm(approved(payment), id(), BASE));
        return receive.execute(execution(payments.findById(payment.getId()).orElseThrow()));
    }

    private RefundExecution stored(RefundExecution received) {
        return executions.findByAttemptId(received.getAttemptId()).orElseThrow();
    }

    private void assertCancellationStopConstraint(ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(DataAccessException.class, failure -> {
            assertThat(failure).hasMessageContaining("chk_refund_execution_cancellation_stop");
            assertThat(failure.getRootCause()).isInstanceOfSatisfying(SQLException.class,
                    sqlFailure -> assertThat(sqlFailure.getErrorCode()).isEqualTo(3819));
        });
    }
}
