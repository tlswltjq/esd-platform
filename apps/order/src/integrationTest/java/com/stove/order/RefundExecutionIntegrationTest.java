package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.payload.RefundExecutionRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.testcontainers.SharedContainers;
import com.stove.order.config.RefundConfiguration;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundCompletionRejected;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.RefundPaymentConfirmation;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import com.stove.order.core.service.ApplyRefundRestoration;
import com.stove.order.core.service.RequestRefundRetry;
import com.stove.order.core.domain.RefundRetryRequest;
import com.stove.order.core.domain.RefundRetryReceipt;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.order.core.service.ApplyRefundPaymentConfirmation;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.RequestRefund;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InfraContainers.MySql.class, RefundConfiguration.class, RefundReceptionIntegrationTest.OutboxConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundExecutionIntegrationTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-26T01:00:00.123456Z");
    private static final Instant RECEIVED_AT = PAID_AT.plusSeconds(3 * 3600);
    private static final Instant STOPPED_AT = PAID_AT.plusSeconds(6 * 3600);
    private static final Instant EXECUTION_AT = STOPPED_AT.plusSeconds(1);
    @Autowired RequestRefund request;
    @Autowired ApplyLicenseGrant applyGrant;
    @Autowired ApplyRefundSuspension apply;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplyRefundPaymentConfirmation confirmMoney;
    @Autowired ApplyRefundRestoration restore;
    @Autowired RequestRefundRetry retry;
    @MockitoSpyBean ProcessedEventGuard inbox;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;

    @BeforeEach void initialize() { clear(); when(clock.instant()).thenReturn(RECEIVED_AT); }
    @AfterEach void clear() {
        reset(inbox);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test void confirmedSuspensionCommitsOneAttemptInboxAndFullExecutionRequest() throws Exception {
        Fixture fixture = fixture(false);
        PaymentResult purchase = fixture.order().paymentResult();
        apply.execute(fixture.input());
        Refund stored = stored(fixture);
        RefundAttempt attempt = attempts.findByAttemptId(stored.getCurrentAttemptId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(stored.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(stored.isAutomaticRefundEligible()).isTrue();
        assertThat(attempt.getSuspensionEventId()).isEqualTo(fixture.input().eventId());
        assertThat(attempt.getSuspensionRequestEventId()).isEqualTo(fixture.input().requestEventId());
        assertThat(attempt.getSuspensionOccurredAt()).isEqualTo(STOPPED_AT);
        assertThat(attempt.getLicenseId()).isEqualTo(fixture.input().licenseId());
        assertThat(attempt.getStartedAt()).isEqualTo(EXECUTION_AT);
        assertThat(inboxCount()).isEqualTo(1);
        Map<String, Object> metadata = jdbc.queryForMap(
                "SELECT event_id,topic,partition_key,aggregate_id FROM outbox_event WHERE event_type='RefundExecutionRequested'");
        assertThat(metadata).containsEntry("event_id", attempt.getExecutionRequestEventId())
                .containsEntry("topic", "stove.order.v1").containsEntry("partition_key", fixture.order().getOrderNo())
                .containsEntry("aggregate_id", fixture.order().getOrderNo());
        RefundExecutionRequestedEvent event = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(
                jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='RefundExecutionRequested'", String.class),
                RefundExecutionRequestedEvent.class);
        assertThat(event).isEqualTo(new RefundExecutionRequestedEvent(attempt.getExecutionRequestEventId(), EXECUTION_AT,
                stored.getRefundId(), attempt.getAttemptId(), fixture.order().getOrderNo(), purchase.paymentId(),
                fixture.order().getBuyerSubject(), fixture.order().getAmount(), fixture.order().getCurrency(), fixture.input().eventId()));
        apply.execute(fixture.input());
        request.execute(fixture.order().getBuyerSubject(), fixture.order().getOrderNo());
        applyGrant.execute(fixture.grant());
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(executionRequests()).isEqualTo(1);
        assertThat(inboxCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseSuspensionRequested'", Integer.class)).isEqualTo(1);
        assertThat(orders.findById(fixture.order().getId()).orElseThrow().paymentResult()).isEqualTo(purchase);
    }

    @Test void outboxInsertFailureRollsBackTheAttemptPointerInboxAndRequest() {
        Fixture fixture = fixture(false);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction ->
                    doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("abort after execution request insert"); })
                            .when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
            assertThatThrownBy(() -> apply.execute(fixture.input())).isInstanceOf(IllegalStateException.class);
            assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
            assertThat(stored(fixture).getCurrentAttemptId()).isNull();
            assertThat(stored(fixture).getSuspensionRequestEventId()).isEqualTo(fixture.input().requestEventId());
            assertThat(attempts.count()).isZero();
            assertThat(executionRequests()).isZero();
            assertThat(inboxCount()).isZero();
        } finally { reset(outbox); }
        apply.execute(fixture.input());
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(executionRequests()).isEqualTo(1);
    }

    @Test void simultaneousCompletionsUseTheOrderLockAndCommitOneExecution() throws Exception {
        Fixture fixture = fixture(false);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                apply.execute(fixture.input()); written.countDown(); await(commit);
            }));
            await(written);
            Future<?> second = pool.submit(() -> { started.countDown(); apply.execute(fixture.input()); });
            await(started);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            commit.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertThat(attempts.count()).isEqualTo(1);
            assertThat(executionRequests()).isEqualTo(1);
            assertThat(inboxCount()).isEqualTo(1);
        } finally {
            commit.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void changedRequestAndChangedCompletionCannotAlterTheOriginalExecution() {
        Fixture fixture = fixture(false);
        LicenseSuspensionConfirmation input = fixture.input();
        LicenseSuspensionConfirmation unrelated = new LicenseSuspensionConfirmation(input.eventId(), input.occurredAt(), id(),
                input.refundId(), input.licenseId(), input.orderNo(), input.buyerSubject(), input.productId(), input.productCode(), input.paymentId());
        assertThatThrownBy(() -> apply.execute(unrelated)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(attempts.count()).isZero(); assertThat(inboxCount()).isZero();
        apply.execute(input);
        String firstAttemptId = stored(fixture).getCurrentAttemptId();
        LicenseSuspensionConfirmation changed = new LicenseSuspensionConfirmation(input.eventId(), input.occurredAt().plusSeconds(1),
                input.requestEventId(), input.refundId(), input.licenseId(), input.orderNo(), input.buyerSubject(), input.productId(),
                input.productCode(), input.paymentId());
        assertThatThrownBy(() -> apply.execute(changed)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(stored(fixture).getCurrentAttemptId()).isEqualTo(firstAttemptId);
        assertThat(attempts.findByAttemptId(firstAttemptId).orElseThrow().getSuspensionOccurredAt()).isEqualTo(STOPPED_AT);
        assertThat(attempts.count()).isEqualTo(1); assertThat(executionRequests()).isEqualTo(1);
    }

    @Test void anUnapprovedManualReviewCannotBeAdvancedByAnExternalCompletion() {
        Fixture fixture = fixture(true);
        assertThatThrownBy(() -> apply.execute(fixture.input())).isInstanceOf(RefundCompletionRejected.class);
        assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.PENDING_REVIEW);
        assertThat(attempts.count()).isZero(); assertThat(executionRequests()).isZero(); assertThat(inboxCount()).isZero();
    }

    @Test void aMissingInboxWitnessIsNotSilentlyRecreatedForAnExistingExecution() {
        Fixture fixture = fixture(false);
        apply.execute(fixture.input());
        jdbc.update("DELETE FROM processed_event WHERE consumer_group=?", ApplyRefundSuspension.CONSUMER_GROUP);
        assertThatThrownBy(() -> apply.execute(fixture.input())).isInstanceOf(RefundCompletionRejected.class);
        assertThat(inboxCount()).isZero();
        assertThat(attempts.count()).isEqualTo(1); assertThat(executionRequests()).isEqualTo(1);
    }

    @Tag("migration")
    @ParameterizedTest @ValueSource(strings = {"valid", "missing", "ambiguous", "wrongBuyer"})
    void migrationRecoversOnlyOneLocallyVerifiedOriginalRequest(String evidence) {
        Fixture fixture = fixture(false);
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "refund_completion_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema
                    + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("14").load().migrate();
            JdbcTemplate old = new JdbcTemplate(source);
            String sourceSchema = jdbc.queryForObject("SELECT DATABASE()", String.class);
            for (String table : List.of("purchase_order", "order_license_grant", "order_refund", "outbox_event")) {
                List<String> names = old.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? "
                        + "AND table_name=? AND generation_expression='' ORDER BY ordinal_position", String.class, schema, table);
                String columns = names.stream().map(name -> "`" + name + "`").collect(Collectors.joining(","));
                old.execute("INSERT INTO `" + schema + "`.`" + table + "` (" + columns + ") SELECT " + columns
                        + " FROM `" + sourceSchema + "`.`" + table + "`");
            }
            if (evidence.equals("missing")) old.update("DELETE FROM outbox_event");
            if (evidence.equals("wrongBuyer")) old.update("UPDATE outbox_event SET payload=JSON_SET(payload,'$.buyerSubject','usr_other')");
            if (evidence.equals("ambiguous")) {
                String duplicateId = id();
                old.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,retry_count,created_at) "
                        + "SELECT ?,aggregate_type,aggregate_id,event_type,topic,partition_key,JSON_SET(payload,'$.eventId',?),status,0,created_at "
                        + "FROM outbox_event WHERE event_type='LicenseSuspensionRequested'", duplicateId, duplicateId);
            }
            Map<String, Object> original = old.queryForMap("SELECT * FROM order_refund");
            Flyway flyway = Flyway.configure().dataSource(source).locations(location).load();
            flyway.migrate(); flyway.validate();
            Map<String, Object> migrated = old.queryForMap("SELECT * FROM order_refund");
            assertThat(migrated).containsAllEntriesOf(original);
            assertThat(migrated.get("suspension_request_event_id")).isEqualTo(evidence.equals("valid") ? fixture.input().requestEventId() : null);
            assertThat(migrated.get("current_attempt_id")).isNull();
            assertThat(old.queryForObject("SELECT COUNT(*) FROM order_refund_attempt", Integer.class)).isZero();
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    @ParameterizedTest @ValueSource(strings = {"PG_QUERY_UNATTRIBUTED", "PG_RESPONSE"})
    void moneyConfirmationInboxAndInvestigationFlagRollBackTogether(String source) {
        Fixture fixture = fixture(false); apply.execute(fixture.input());
        RefundPaymentConfirmation previous = confirmation(fixture);
        RefundPaymentConfirmation confirmation = new RefundPaymentConfirmation(previous.eventId(), previous.occurredAt(), previous.refundId(),
                previous.attemptId(), previous.requestEventId(), previous.orderNo(), previous.paymentId(), previous.buyerSubject(),
                previous.amount(), previous.currency(), source, previous.providerTransactionId(), previous.cancellationIds());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            boolean first = (Boolean) call.callRealMethod();
            if (ApplyRefundPaymentConfirmation.CONSUMER_GROUP.equals(call.getArgument(1)))
                throw new IllegalStateException("after confirmation inbox insert");
            return first;
        }).when(inbox).firstDelivery(anyString(), anyString(), anyString()));
        assertThatThrownBy(() -> confirmMoney.execute(confirmation)).isInstanceOf(IllegalStateException.class);
        assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(stored(fixture).getCompletedAt()).isNull();
        assertThat(attempts.findByAttemptId(confirmation.attemptId()).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        assertThat(attempts.findByAttemptId(confirmation.attemptId()).orElseThrow().isReconciliationRequired()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class,
                ApplyRefundPaymentConfirmation.CONSUMER_GROUP)).isZero();
        reset(inbox); confirmMoney.execute(confirmation);
        assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(attempts.findByAttemptId(confirmation.attemptId()).orElseThrow().isReconciliationRequired())
                .isEqualTo("PG_QUERY_UNATTRIBUTED".equals(source));
        apply.execute(fixture.input());
        assertThat(executionRequests()).isEqualTo(1);
    }

    @Test void concurrentMoneyConfirmationsPreserveOneCompletionAndOriginalPaidFacts() throws Exception {
        Fixture fixture = fixture(false); apply.execute(fixture.input());
        PaymentResult original = fixture.order().paymentResult();
        RefundPaymentConfirmation confirmation = confirmation(fixture);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> work = List.of(pool.submit(() -> { start.await(); confirmMoney.execute(confirmation); return null; }),
                    pool.submit(() -> { start.await(); confirmMoney.execute(confirmation); return null; }));
            start.countDown(); for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
            assertThat(stored(fixture).getPaymentConfirmationEventId()).isEqualTo(confirmation.eventId());
            assertThat(stored(fixture).getCompletedAt()).isEqualTo(confirmation.occurredAt());
            assertThat(orders.findById(fixture.order().getId()).orElseThrow().paymentResult()).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class,
                    ApplyRefundPaymentConfirmation.CONSUMER_GROUP)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void changedEvidenceCannotReplaceTheFirstCompletionEvenWithTheSameEventId() {
        Fixture fixture = fixture(false); apply.execute(fixture.input());
        RefundPaymentConfirmation first = confirmation(fixture); confirmMoney.execute(first);
        RefundPaymentConfirmation changed = new RefundPaymentConfirmation(first.eventId(), first.occurredAt(), first.refundId(),
                first.attemptId(), first.requestEventId(), first.orderNo(), first.paymentId(), first.buyerSubject(), first.amount(),
                first.currency(), first.source(), first.providerTransactionId(), List.of("different-cancel"));
        assertThatThrownBy(() -> confirmMoney.execute(changed)).isInstanceOf(RefundCompletionRejected.class);
        assertThat(stored(fixture).getPaymentConfirmationFingerprint()).isEqualTo(first.fingerprint());
    }

    @Test void verifiedFailureCreatesOneRestorationRequestAndOnlyActualRestorationEndsTheFailedWorkflow() {
        Fixture fixture = fixture(false); apply.execute(fixture.input()); RefundFailureConfirmation failure = failure(fixture);
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(2)); restore.execute(failure); restore.execute(failure);
        Refund current = stored(fixture); RefundAttempt attempt = attempts.findByAttemptId(current.getCurrentAttemptId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_RESTORATION);
        assertThat(attempt.getRestoredAt()).isNull(); assertThat(current.getCompletedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseRestorationRequested'", Integer.class)).isEqualTo(1);
        LicenseRestorationConfirmation completed = restored(fixture, attempt); restore.execute(completed); restore.execute(completed);
        assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(attempts.findByAttemptId(attempt.getAttemptId()).orElseThrow().getRestoredAt()).isEqualTo(completed.occurredAt());
        assertThat(stored(fixture).getReceivedAt()).isEqualTo(RECEIVED_AT); assertThat(stored(fixture).isAutomaticRefundEligible()).isTrue();
        assertThat(orders.findById(fixture.order().getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(attempts.count()).isEqualTo(1); assertThat(executionRequests()).isEqualTo(1);
    }

    @Test void failureAttemptInboxAndRestorationRequestRollbackTogetherAndTheSameFailureCanResume() {
        Fixture fixture = fixture(false); apply.execute(fixture.input()); RefundFailureConfirmation failure = failure(fixture);
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(2));
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
                call.callRealMethod(); orders.flush(); throw new IllegalStateException("after failure and restoration request flush");
            }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
            assertThatThrownBy(() -> restore.execute(failure)).isInstanceOf(IllegalStateException.class);
            assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
            assertThat(attempts.findByAttemptId(failure.attemptId()).orElseThrow().getFailureEventId()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='order-refund-restoration'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseRestorationRequested'", Integer.class)).isZero();
        } finally { reset(outbox); }
        restore.execute(failure); assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_RESTORATION);
    }

    @Test void aMoneyConfirmationCommittedFirstRejectsALateFailureAndNeverRequestsRestoration() {
        Fixture fixture = fixture(false); apply.execute(fixture.input()); confirmMoney.execute(confirmation(fixture));
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(2));
        assertThatThrownBy(() -> restore.execute(failure(fixture))).isInstanceOf(RefundCompletionRejected.class);
        assertThat(stored(fixture).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseRestorationRequested'", Integer.class)).isZero();
    }

    @Test void anExplicitRetryAfterTheOriginalWindowKeepsEligibilityAndNeedsANewActualSuspension() throws Exception {
        RestoredFixture old = restoredFixture(); RefundRetryRequest input = retryRequest(old);
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(86400));
        RefundRetryReceipt receipt = retry.execute(input); assertThat(retry.execute(input)).isEqualTo(receipt);
        Refund pending = stored(old.fixture());
        assertThat(pending.getStatus()).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(pending.getCurrentAttemptId()).isEqualTo(old.previous().getAttemptId());
        assertThat(pending.getReceivedAt()).isEqualTo(RECEIVED_AT); assertThat(pending.isAutomaticRefundEligible()).isTrue();
        assertThat(executionRequests()).isEqualTo(1);
        apply.execute(old.fixture().input()); restore.execute(old.failure()); restore.execute(old.restored());
        assertThat(stored(old.fixture()).getSuspensionRequestEventId()).isEqualTo(receipt.suspensionRequestEventId());
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(86402));
        LicenseSuspensionConfirmation stop = retrySuspension(old, receipt); apply.execute(stop); apply.execute(stop);
        RefundAttempt next = attempts.findByAttemptId(stored(old.fixture()).getCurrentAttemptId()).orElseThrow();
        assertThat(next.getAttemptNo()).isEqualTo(2); assertThat(next.getPreviousAttemptId()).isEqualTo(old.previous().getAttemptId());
        assertThat(next.getSuspensionEventId()).isEqualTo(stop.eventId()); assertThat(executionRequests()).isEqualTo(2);
        apply.execute(old.fixture().input()); restore.execute(old.failure()); restore.execute(old.restored());
        assertThat(retry.execute(input)).isEqualTo(receipt); assertThat(stored(old.fixture()).getCurrentAttemptId()).isEqualTo(next.getAttemptId());
        assertThat(attempts.findByAttemptId(old.previous().getAttemptId()).orElseThrow().getFailureFingerprint()).isEqualTo(old.failure().fingerprint());
        RefundExecutionRequestedEvent event = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(
                jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, next.getExecutionRequestEventId()), RefundExecutionRequestedEvent.class);
        assertThat(event.previousAttemptId()).isEqualTo(old.previous().getAttemptId());
    }

    @Test void concurrentExplicitRequestsNameOnePredecessorAndCommitOneRetryReceipt() throws Exception {
        RestoredFixture old = restoredFixture(); when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(86400));
        RefundRetryRequest input = retryRequest(old); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RefundRetryReceipt> first = pool.submit(() -> { start.await(); return retry.execute(input); });
            Future<RefundRetryReceipt> second = pool.submit(() -> { start.await(); return retry.execute(input); });
            start.countDown(); assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseSuspensionRequested'", Integer.class)).isEqualTo(2);
            assertThat(attempts.count()).isEqualTo(1); assertThat(executionRequests()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void retryReceiptStateAndOutboxRollbackTogetherThenResumeWithoutMovingTheFirstEligibility() {
        RestoredFixture old = restoredFixture(); when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(86400));
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
                call.callRealMethod(); orders.flush(); throw new IllegalStateException("after retry receipt and outbox flush");
            }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
            assertThatThrownBy(() -> retry.execute(retryRequest(old))).isInstanceOf(IllegalStateException.class);
            assertThat(stored(old.fixture()).getStatus()).isEqualTo(RefundStatus.FAILED);
            assertThat(attempts.findByAttemptId(old.previous().getAttemptId()).orElseThrow().getRetryRequestedAt()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseSuspensionRequested'", Integer.class)).isEqualTo(1);
        } finally { reset(outbox); }
        RefundRetryReceipt receipt = retry.execute(retryRequest(old));
        assertThat(stored(old.fixture()).getSuspensionRequestEventId()).isEqualTo(receipt.suspensionRequestEventId());
        assertThat(stored(old.fixture()).getReceivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test void restorationRequestAloneAndAnotherBuyerCannotAuthorizeARetry() {
        Fixture fixture = fixture(false); apply.execute(fixture.input()); RefundFailureConfirmation failed = failure(fixture);
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(2)); restore.execute(failed);
        RefundAttempt previous = attempts.findByAttemptId(stored(fixture).getCurrentAttemptId()).orElseThrow();
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(86400));
        assertThatThrownBy(() -> retry.execute(new RefundRetryRequest(fixture.order().getBuyerSubject(), fixture.order().getOrderNo(), previous.getAttemptId())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> retry.execute(new RefundRetryRequest("another-buyer", fixture.order().getOrderNo(), previous.getAttemptId())))
                .isInstanceOf(BusinessException.class);
        assertThat(attempts.findByAttemptId(previous.getAttemptId()).orElseThrow().getRetryRequestedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='LicenseSuspensionRequested'", Integer.class)).isEqualTo(1);
    }

    private RestoredFixture restoredFixture() {
        Fixture fixture = fixture(false); apply.execute(fixture.input()); RefundFailureConfirmation failed = failure(fixture);
        when(clock.instant()).thenReturn(EXECUTION_AT.plusSeconds(2)); restore.execute(failed);
        RefundAttempt previous = attempts.findByAttemptId(stored(fixture).getCurrentAttemptId()).orElseThrow();
        LicenseRestorationConfirmation completed = restored(fixture, previous); restore.execute(completed);
        return new RestoredFixture(fixture, previous, failed, completed);
    }
    private RefundRetryRequest retryRequest(RestoredFixture old) {
        return new RefundRetryRequest(old.fixture().order().getBuyerSubject(), old.fixture().order().getOrderNo(), old.previous().getAttemptId());
    }
    private LicenseSuspensionConfirmation retrySuspension(RestoredFixture old, RefundRetryReceipt receipt) {
        Order order = old.fixture().order(); return new LicenseSuspensionConfirmation(id(), EXECUTION_AT.plusSeconds(86401), receipt.suspensionRequestEventId(),
                receipt.refundId(), old.previous().getLicenseId(), order.getOrderNo(), order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId());
    }
    private record RestoredFixture(Fixture fixture, RefundAttempt previous, RefundFailureConfirmation failure, LicenseRestorationConfirmation restored) { }

    private RefundFailureConfirmation failure(Fixture fixture) {
        Refund current = stored(fixture); RefundAttempt attempt = attempts.findByAttemptId(current.getCurrentAttemptId()).orElseThrow();
        return new RefundFailureConfirmation(id(), EXECUTION_AT.plusSeconds(1), current.getRefundId(), attempt.getAttemptId(), attempt.getExecutionRequestEventId(),
                current.getOrderNo(), fixture.order().getPaymentId(), fixture.order().getBuyerSubject(), fixture.order().getAmount(), fixture.order().getCurrency(),
                "pg-original", "cancel-own", "a".repeat(64));
    }
    private LicenseRestorationConfirmation restored(Fixture fixture, RefundAttempt attempt) {
        return new LicenseRestorationConfirmation(id(), EXECUTION_AT.plusSeconds(3), attempt.getRestorationRequestEventId(), attempt.getRefundId(),
                attempt.getAttemptId(), attempt.getFailureEventId(), attempt.getLicenseId(), fixture.order().getOrderNo(), fixture.order().getBuyerSubject(),
                fixture.order().getProductId(), fixture.order().getProductCode(), fixture.order().getPaymentId(), attempt.getSuspensionRequestEventId(), attempt.getSuspensionEventId());
    }

    private RefundPaymentConfirmation confirmation(Fixture fixture) {
        Refund current = stored(fixture);
        RefundAttempt attempt = attempts.findByAttemptId(current.getCurrentAttemptId()).orElseThrow();
        return new RefundPaymentConfirmation(id(), EXECUTION_AT.plusSeconds(1), current.getRefundId(), attempt.getAttemptId(),
                attempt.getExecutionRequestEventId(), current.getOrderNo(), fixture.order().getPaymentId(), fixture.order().getBuyerSubject(),
                fixture.order().getAmount(), fixture.order().getCurrency(), "PG_QUERY_UNATTRIBUTED", "pg-original", List.of("cancel-1"));
    }

    private Fixture fixture(boolean review) {
        Order paid = order();
        paid.apply(new PaymentResult(id(), PAID_AT, paid.getOrderNo(), paid.getRequestEventId(), id(),
                paid.getBuyerSubject(), paid.getAmount(), paid.getCurrency(), OrderStatus.PAID, null, null));
        orders.saveAndFlush(paid);
        LicenseGrant grant = new LicenseGrant(id(), PAID_AT.plusSeconds(60), id(), paid.getOrderNo(), paid.getBuyerSubject(),
                paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        applyGrant.execute(grant);
        when(clock.instant()).thenReturn(review ? PAID_AT.plusSeconds(5 * 3600) : RECEIVED_AT);
        Refund refund = request.execute(paid.getBuyerSubject(), paid.getOrderNo());
        LicenseSuspensionConfirmation input = new LicenseSuspensionConfirmation(id(), STOPPED_AT,
                refund.getSuspensionRequestEventId() == null ? id() : refund.getSuspensionRequestEventId(), refund.getRefundId(),
                grant.licenseId(), paid.getOrderNo(), paid.getBuyerSubject(), paid.getProductId(), paid.getProductCode(), paid.getPaymentId());
        when(clock.instant()).thenReturn(EXECUTION_AT);
        return new Fixture(paid, refund, grant, input);
    }

    private Refund stored(Fixture fixture) { return refunds.findByOrderNo(fixture.order().getOrderNo()).orElseThrow(); }
    private int executionRequests() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='RefundExecutionRequested'", Integer.class); }
    private int inboxCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class, ApplyRefundSuspension.CONSUMER_GROUP); }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private record Fixture(Order order, Refund refund, LicenseGrant grant, LicenseSuspensionConfirmation input) {}
}
