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
import com.stove.common.kafka.contract.payload.RefundExecutionPreparedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.kafka.contract.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

class RefundPreparationIntegrationTest extends PaymentIntegrationTestBase {
    private static final Instant BASE = Payment.now();
    @Autowired ReceiveOrderPayment orderReceipt;
    @Autowired ReceiveRefundExecution receive;
    @Autowired RecordRefundCancellationPreparation prepare;
    @Autowired RecordRefundPreparation preparationEvents;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean OutboxRecorder outbox;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoSpyBean RefundExecutionRepository executions;

    @BeforeEach void initialize() { clear(); when(clock.instant()).thenReturn(BASE); }

    @AfterEach void clear() {
        reset(executions,outbox);
        jdbc.update("DELETE FROM payment_pg_webhook");
        jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void firstPreparationAndItsOneOutboxFactCarryTheExactOriginalEvidence() throws Exception {
        RefundExecution execution=received(); when(clock.instant()).thenReturn(BASE.plusSeconds(1800)); prepare.execute(execution.getAttemptId());
        RefundExecution stored=stored(execution); Map<String,Object> row=jdbc.queryForMap("SELECT event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status FROM outbox_event WHERE event_type='RefundExecutionPrepared'");
        RefundExecutionPreparedEvent expected=new RefundExecutionPreparedEvent(stored.getPreparationEventId(),BASE.plusSeconds(1800),execution.getRefundId(),execution.getAttemptId(),execution.getRequestEventId(),
                execution.getOrderNo(),execution.getPaymentId(),execution.getBuyerSubject(),execution.getAmount(),execution.getCurrency(),execution.getLicenseSuspensionEventId(),execution.getPreviousAttemptId(),BASE.plusSeconds(1800),BASE.plusSeconds(12600));
        assertThat(mapper.readTree(row.get("payload").toString())).isEqualTo(mapper.readTree(mapper.writeValueAsString(expected)));
        assertThat(row).containsEntry("event_id",expected.eventId()).containsEntry("aggregate_type","Payment").containsEntry("aggregate_id",execution.getOrderNo())
                .containsEntry("event_type",expected.eventType()).containsEntry("topic",expected.topic()).containsEntry("partition_key",expected.partitionKey()).containsEntry("status","PENDING");
        assertThat(stored.getStatus()).isEqualTo(RefundExecutionStatus.PENDING); assertThat(stored.getCancellationRequestBody()).isNull();
        assertThat(stored.getRefundConfirmedAt()).isNull(); assertThat(stored.getFirstCancellationPreparedAt()).isEqualTo(BASE.plusSeconds(1800));
        assertThat(payments.findByPaymentId(execution.getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test void redeliveryRestartAndLatePreparationCannotMoveTheOriginalDeadlineOrRepublishIt() {
        RefundExecution execution=received(); prepare.execute(execution.getAttemptId()); String event=stored(execution).getPreparationEventId();
        Instant next=stored(execution).getLookupSchedule().getNextAt(); when(clock.instant()).thenReturn(BASE.plusSeconds(14400));
        assertThat(prepare.execute(execution.getAttemptId()).automaticCancellationStopped()).isTrue();
        RefundExecution finalRecord=stored(execution); assertThat(finalRecord.getPreparationEventId()).isEqualTo(event);
        assertThat(finalRecord.getFirstCancellationPreparedAt()).isEqualTo(BASE); assertThat(finalRecord.cancellationResumeDeadline()).isEqualTo(BASE.plusSeconds(10800));
        assertThat(finalRecord.getLookupSchedule().getNextAt()).isEqualTo(next); assertThat(preparedFacts()).isEqualTo(1);
    }

    @Test void concurrentPreparationsCommitOneIdentityAndOneOutboxFact() throws Exception {
        RefundExecution execution=received(); ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> first=pool.submit(()->{start.await();prepare.execute(execution.getAttemptId());return null;});
            Future<?> second=pool.submit(()->{start.await();prepare.execute(execution.getAttemptId());return null;}); start.countDown(); first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
            assertThat(preparedFacts()).isEqualTo(1); assertThat(stored(execution).getPreparationEventId()).isNotBlank();
        } finally { pool.shutdownNow(); }
    }

    @Test void outboxFailureAfterRealFlushRollsBackPreparationPublicationAndLookupSchedule() {
        RefundExecution execution=received();
        new TransactionTemplate(transactions).executeWithoutResult(tx->doAnswer(call->{call.callRealMethod();entityManager.flush();
            assertThat(preparedFacts()).isEqualTo(1); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_execution WHERE preparation_event_id IS NOT NULL",Integer.class)).isEqualTo(1);
            throw new IllegalStateException("after preparation state and outbox flush");}).when(outbox).record(anyString(),anyString(),any(DomainEvent.class)));
        assertThatThrownBy(()->prepare.execute(execution.getAttemptId())).isInstanceOf(IllegalStateException.class);
        RefundExecution rolledBack=stored(execution); assertThat(rolledBack.getFirstCancellationPreparedAt()).isNull();assertThat(rolledBack.getPreparationEventId()).isNull();
        assertThat(rolledBack.getLookupSchedule().getNextAt()).isNull();assertThat(preparedFacts()).isZero();
        reset(outbox);when(clock.instant()).thenReturn(BASE.plusSeconds(30));prepare.execute(execution.getAttemptId());
        assertThat(stored(execution).getFirstCancellationPreparedAt()).isEqualTo(BASE.plusSeconds(30));assertThat(preparedFacts()).isEqualTo(1);
    }

    @Test void aKnownPreparationWithoutAPublicationUsesTheOldTimeRatherThanTheCurrentClock() {
        RefundExecution execution=received(); prepare.execute(execution.getAttemptId()); jdbc.update("DELETE FROM outbox_event WHERE event_type='RefundExecutionPrepared'");
        jdbc.update("UPDATE refund_execution SET preparation_event_id=NULL WHERE attempt_id=?",execution.getAttemptId()); when(clock.instant()).thenReturn(BASE.plusSeconds(3600));
        prepare.execute(execution.getAttemptId()); assertThat(preparedFacts()).isEqualTo(1);
        assertThat(stored(execution).getFirstCancellationPreparedAt()).isEqualTo(BASE); assertThat(stored(execution).cancellationResumeDeadline()).isEqualTo(BASE.plusSeconds(10800));
    }

    @Test void v26PublishesOnlyKnownPreparationFactsAndPreservesOldFinancialStateSchedulesAndOutbox() throws Exception {
        RefundExecution first=received(); when(clock.instant()).thenReturn(BASE.plusSeconds(30)); prepare.execute(first.getAttemptId());
        when(clock.instant()).thenReturn(BASE); RefundExecution old=received(); prepare.execute(old.getAttemptId()); when(clock.instant()).thenReturn(BASE.plusSeconds(14400)); prepare.execute(old.getAttemptId());
        when(clock.instant()).thenReturn(BASE); RefundExecution unknown=received(); MySQLContainer<?> mysql=SharedContainers.MYSQL;
        String schema="preparation_upgrade_"+id().replace("-","");String server="jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/";String options="?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource(server+mysql.getDatabaseName()+options,"root",mysql.getPassword()));admin.execute("CREATE DATABASE "+schema);
        try {
            DriverManagerDataSource source=new DriverManagerDataSource(server+schema+options,"root",mysql.getPassword());JdbcTemplate legacy=new JdbcTemplate(source);
            String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();Flyway.configure().dataSource(source).locations(location).target("25").load().migrate();
            List<String> columns=legacy.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='refund_execution' AND GENERATION_EXPRESSION='' ORDER BY ORDINAL_POSITION",String.class,schema);
            String list=String.join(",",columns);
            String paymentColumns=String.join(",",legacy.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='purchase_payment' AND GENERATION_EXPRESSION='' ORDER BY ORDINAL_POSITION",String.class,schema));
            for (RefundExecution value:List.of(first,old,unknown)) {
                legacy.update("INSERT INTO purchase_payment("+paymentColumns+") SELECT "+paymentColumns+" FROM "+mysql.getDatabaseName()+".purchase_payment WHERE payment_id=?",value.getPaymentId());
                legacy.update("INSERT INTO refund_execution("+list+") SELECT "+list+" FROM "+mysql.getDatabaseName()+".refund_execution WHERE attempt_id=?",value.getAttemptId());
                legacy.update("INSERT INTO refund_execution_trace SELECT * FROM "+mysql.getDatabaseName()+".refund_execution_trace WHERE attempt_id=?",value.getAttemptId());
            }
            legacy.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at) VALUES(?,'Payment','existing','ExistingFact','stove.payment.v1','existing','{\"keep\":true}','SENT',NOW(6))",id());
            List<Map<String,Object>> before=legacy.queryForList("SELECT * FROM refund_execution ORDER BY id");List<Map<String,Object>> paymentsBefore=legacy.queryForList("SELECT * FROM purchase_payment ORDER BY id");
            List<Map<String,Object>> outboxBefore=legacy.queryForList("SELECT * FROM outbox_event ORDER BY id"); Flyway flyway=Flyway.configure().dataSource(source).locations(location).target("26").load();flyway.migrate();flyway.validate();
            List<Map<String,Object>> after=legacy.queryForList("SELECT * FROM refund_execution ORDER BY id");for (int index=0;index<before.size();index++) assertThat(after.get(index)).containsAllEntriesOf(before.get(index));
            assertThat(legacy.queryForList("SELECT * FROM purchase_payment ORDER BY id")).isEqualTo(paymentsBefore);
            assertThat(legacy.queryForList("SELECT * FROM outbox_event WHERE event_type='ExistingFact' ORDER BY id")).isEqualTo(outboxBefore);
            assertThat(legacy.queryForObject("SELECT preparation_event_id FROM refund_execution WHERE attempt_id=?",String.class,unknown.getAttemptId())).isNull();
            assertThat(legacy.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionPrepared'",Integer.class)).isEqualTo(2);
            for (RefundExecution value:List.of(first,old)) {
                RefundExecution original=stored(value); Map<String,Object> row=legacy.queryForMap("SELECT o.* FROM outbox_event o JOIN refund_execution e ON e.preparation_event_id=o.event_id WHERE e.attempt_id=?",value.getAttemptId());
                RefundExecutionPreparedEvent expected=new RefundExecutionPreparedEvent(row.get("event_id").toString(),original.getFirstCancellationPreparedAt(),value.getRefundId(),value.getAttemptId(),value.getRequestEventId(),value.getOrderNo(),value.getPaymentId(),value.getBuyerSubject(),value.getAmount(),value.getCurrency(),value.getLicenseSuspensionEventId(),value.getPreviousAttemptId(),original.getFirstCancellationPreparedAt(),original.cancellationResumeDeadline());
                assertThat(mapper.readValue(row.get("payload").toString(),RefundExecutionPreparedEvent.class)).isEqualTo(expected);
                assertThat(row).containsEntry("aggregate_type","Payment").containsEntry("aggregate_id",value.getOrderNo()).containsEntry("topic",expected.topic()).containsEntry("partition_key",value.getOrderNo()).containsEntry("status","PENDING");
            }
            List<Map<String,Object>> once=legacy.queryForList("SELECT * FROM outbox_event ORDER BY id");flyway.migrate();assertThat(legacy.queryForList("SELECT * FROM outbox_event ORDER BY id")).isEqualTo(once);
        } finally { admin.execute("DROP DATABASE "+schema); }
    }

    private int preparedFacts() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionPrepared'",Integer.class); }
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
