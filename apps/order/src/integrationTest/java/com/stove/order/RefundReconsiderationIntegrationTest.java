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

class RefundReconsiderationIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final AtomicLong PRODUCTS = new AtomicLong(1000);
    @MockitoSpyBean OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RequestRefund request;
    @Autowired DecideRefund decide;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplyLicenseGrant grant;
    @Autowired ApplyRefundSuspension suspension;
    @Autowired ApplyRefundPaymentConfirmation payment;
    @Autowired ApplyRefundRestoration restore;
    @Autowired RefundAttemptRepository attempts;
    @MockitoSpyBean RefundReviewRoundRepository rounds;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoBean Clock clock;
    @MockitoBean CatalogProducts catalog;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        reset(orders, rounds, outbox);
        when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC"); jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
    }

    @Test void ownerCanRequestANewReviewWhileTheFirstRejectionAndPurchaseRemainStored() {
        Fixture fixture = rejected(); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        Response response = reconsider(fixture, id(), fixture.refund().getRefundId(), "재설치 후에도 실행 오류가 계속됩니다");
        assertThat(response.status()).isEqualTo(202);
        assertThat(response.data().path("previousReviewId").asText()).isEqualTo(fixture.refund().getRefundId());
        assertThat(response.data().path("reviewNo").asLong()).isEqualTo(2);
        Refund stored = refunds.findByOrderNo(fixture.order().getOrderNo()).orElseThrow();
        assertThat(stored.getStatus().name()).isEqualTo("PENDING_REVIEW"); assertThat(stored.reviewReceipt()).isEqualTo(fixture.refund().reviewReceipt());
        assertThat(stored.getReceivedAt()).isEqualTo(BASE); assertThat(stored.isAutomaticRefundEligible()).isFalse();
        assertThat(orders.findById(fixture.order().getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test void sevenDayBoundaryIsAcceptedAndTheSameRequestStillReturnsItsReceiptAfterExpiry() {
        Fixture fixture = rejected(); String command = id(); when(clock.instant()).thenReturn(BASE.plusSeconds(7 * 86400));
        Response first = reconsider(fixture, command, fixture.refund().getRefundId(), "이전 조치 후에도 문제가 남았습니다");
        assertThat(first.status()).isEqualTo(202);
        when(clock.instant()).thenReturn(BASE.plusSeconds(8 * 86400));
        assertThat(reconsider(fixture, command, fixture.refund().getRefundId(), "이전 조치 후에도 문제가 남았습니다").data()).isEqualTo(first.data());
        assertThat(verdict(fixture, first.data().path("reviewId").asText(), id(), "APPROVED", "접수 뒤 심사 지연", "admin").status()).isEqualTo(200);
        assertThat(current(fixture).getStatus().name()).isEqualTo("PENDING_LICENSE_GRANT");
    }

    @Test void lockWaitCannotMoveAWithinDeadlineApplicationBeyondItsOriginalReceiptTime() throws Exception {
        Fixture fixture = rejected(); Instant deadline = BASE.plusSeconds(7 * 86400); AtomicReference<Instant> now = new AtomicReference<>(deadline);
        when(clock.instant()).thenAnswer(call -> now.get()); CountDownLatch attempting = new CountDownLatch(1); ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Response> future = new TransactionTemplate(transactions).execute(tx -> {
                orders.lockByOrderNo(fixture.order().getOrderNo()).orElseThrow();
                doAnswer(call -> {
                    attempting.countDown(); return Optional.ofNullable(entityManager.find(Order.class, fixture.order().getId(), LockModeType.PESSIMISTIC_WRITE));
                }).when(orders).lockByOrderNo(fixture.order().getOrderNo());
                Future<Response> received = pool.submit(() -> reconsider(fixture, id(), fixture.refund().getRefundId(), "기한 내 추가 사유"));
                await(attempting); now.set(deadline.plusSeconds(60)); return received;
            });
            Response response = future.get(20, TimeUnit.SECONDS); assertThat(response.status()).isEqualTo(202);
            assertThat(response.data().path("receivedAt").asText()).isEqualTo(deadline.toString());
        } finally { reset(orders); pool.shutdownNow(); }
    }

    @Test void expiredOrMalformedAndUnownedRequestsNeverChangeTheRejection() {
        Fixture fixture = rejected(); when(clock.instant()).thenReturn(BASE.plusSeconds(7 * 86400).plusNanos(1000));
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(reconsider(fixture, id(), fixture.refund().getRefundId(), "추가 설명").status()).isEqualTo(409);
        when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        for (String reason : List.of("", " ", "x".repeat(501)))
            assertThat(reconsider(fixture, id(), fixture.refund().getRefundId(), reason).status()).isEqualTo(400);
        Map<String, String> valid = application(id(), fixture.refund().getRefundId(), "추가 설명");
        assertThat(http("POST", path(fixture) + "/reconsiderations", Map.of(), "usr_buyer", "MEMBER").status()).isEqualTo(400);
        assertThat(http("POST", path(fixture) + "/reconsiderations", valid, "another", "MEMBER").status()).isEqualTo(404);
        assertThat(http("GET", path(fixture) + "/reviews", null, "another", "MEMBER").status()).isEqualTo(404);
        assertThat(http("POST", "/api/v1/orders/" + id() + "/refund/reconsiderations", valid, "usr_buyer", "MEMBER").status()).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void allNewRoutesEnforceTheirJwtRolesAndHistoryPagination() {
        Fixture fixture = rejected(); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        Map<String, List<Map<String, Object>>> before = snapshot(); Map<String, String> body = application(id(), fixture.refund().getRefundId(), "추가 사유");
        for (String role : new String[]{null, "ADMIN", "CREATOR"}) {
            int expected = role == null ? 401 : 403;
            assertThat(http("POST", path(fixture) + "/reconsiderations", body, "actor", role).status()).isEqualTo(expected);
            assertThat(http("GET", path(fixture) + "/reviews", null, "actor", role).status()).isEqualTo(expected);
        }
        for (String role : new String[]{null, "MEMBER", "CREATOR"}) {
            int expected = role == null ? 401 : 403;
            assertThat(http("GET", adminPath(fixture) + "/reviews", null, "actor", role).status()).isEqualTo(expected);
            assertThat(http("POST", adminPath(fixture) + "/reconsiderations/" + id() + "/decision", Map.of(), "actor", role).status()).isEqualTo(expected);
        }
        for (String query : List.of("?page=-1", "?size=0", "?size=101")) {
            assertThat(http("GET", path(fixture) + "/reviews" + query, null, "usr_buyer", "MEMBER").status()).isEqualTo(400);
            assertThat(http("GET", adminPath(fixture) + "/reviews" + query, null, "admin", "ADMIN").status()).isEqualTo(400);
        }
        for (String bearer : List.of(token("usr_buyer", "MEMBER", ISSUER, "wrong", Instant.now().plusSeconds(60)),
                token("usr_buyer", "MEMBER", ISSUER, "esd-api", Instant.now().minusSeconds(120))))
            assertThat(send("POST", path(fixture) + "/reconsiderations", body, bearer).status()).isEqualTo(401);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void customerAndAdministratorViewsShowCurrentAndHistoricalFactsWithoutDisclosingInternalAuditToTheBuyer() {
        Fixture fixture = rejected(); JsonNode first = customer(fixture).data();
        assertThat(first.path("currentReview").path("reviewId").asText()).isEqualTo(fixture.refund().getRefundId());
        assertThat(first.path("reconsiderationDeadlineAt").asText()).isEqualTo(BASE.plusSeconds(7 * 86400).toString());
        assertThat(first.path("reconsiderationAvailable").asBoolean()).isTrue();
        when(clock.instant()).thenReturn(BASE.plusSeconds(1)); String command = id(); Response applied = reconsider(fixture, command, fixture.refund().getRefundId(), "고객 추가 설명");
        JsonNode current = customer(fixture).data(); assertThat(current.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(current.path("rejectionReason").isNull()).isTrue(); assertThat(current.path("reconsiderationAvailable").asBoolean()).isFalse();
        assertThat(current.path("currentReview").path("requestId").asText()).isEqualTo(command);
        Response history = http("GET", path(fixture) + "/reviews?size=1&page=1", null, "usr_buyer", "MEMBER");
        assertThat(history.status()).isEqualTo(200); assertThat(history.cacheControl()).isEqualTo("no-store");
        assertThat(history.data().path("totalElements").asInt()).isEqualTo(2);
        JsonNode row = history.data().path("content").get(0); assertThat(row.path("reviewId").asText()).isEqualTo(applied.data().path("reviewId").asText());
        for (String hidden : List.of("operatorSubject", "review", "decisionRequestId")) assertThat(row.has(hidden)).isFalse();
        JsonNode admin = http("GET", adminPath(fixture), null, "usr_admin", "ADMIN").data();
        assertThat(admin.path("review").path("decision").asText()).isEqualTo("REJECTED");
        assertThat(admin.path("currentReview").path("customerReason").asText()).isEqualTo("고객 추가 설명");
        JsonNode adminHistory = http("GET", adminPath(fixture) + "/reviews", null, "usr_admin", "ADMIN").data();
        assertThat(adminHistory.path("content").get(0).path("review").path("operatorSubject").asText()).isEqualTo("usr_admin");
    }

    @Test void unlimitedReconsiderationsPreserveEarlierRejectionsAndDoNotResetOriginalEligibility() {
        Fixture fixture = rejected(); String previous = fixture.refund().getRefundId();
        for (int reviewNo = 2; reviewNo <= 18; reviewNo++) {
            Instant at = BASE.plusSeconds((reviewNo - 1L) * 6 * 86400);
            when(clock.instant()).thenReturn(at);
            Response applied = reconsider(fixture, id(), previous, "고객의 재심사 사유"); assertThat(applied.status()).isEqualTo(202);
            assertThat(applied.data().path("reviewNo").asLong()).isEqualTo(reviewNo);
            String review = applied.data().path("reviewId").asText(); when(clock.instant()).thenReturn(at.plusSeconds(1));
            assertThat(verdict(fixture, review, id(), "REJECTED", "재심사 반려 " + reviewNo, "admin").status()).isEqualTo(200); previous = review;
        }
        JsonNode history = http("GET", path(fixture) + "/reviews?size=100", null, "usr_buyer", "MEMBER").data();
        assertThat(history.path("totalElements").asInt()).isEqualTo(18);
        Refund stored = current(fixture); assertThat(stored.reviewReceipt()).isEqualTo(fixture.refund().reviewReceipt());
        assertThat(stored.getReceivedAt()).isEqualTo(BASE); assertThat(stored.isAutomaticRefundEligible()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test void oldApplicationsAndDecisionsRemainReceiptsAndCannotAffectTheNextPendingRound() {
        Fixture fixture = rejected(); String initialId = fixture.refund().reviewReceipt().requestId();
        when(clock.instant()).thenReturn(BASE.plusSeconds(1)); String application = id();
        Response first = reconsider(fixture, application, fixture.refund().getRefundId(), "추가 설명"); String second = first.data().path("reviewId").asText();
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); String decision = id(); Response rejected = verdict(fixture, second, decision, "REJECTED", "회차 반려", "admin");
        when(clock.instant()).thenReturn(BASE.plusSeconds(3)); Response third = reconsider(fixture, id(), second, "다음 설명");
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(reconsider(fixture, application, fixture.refund().getRefundId(), "추가 설명").data()).isEqualTo(first.data());
        assertThat(verdict(fixture, second, decision, "REJECTED", "회차 반려", "admin").data()).isEqualTo(rejected.data());
        assertThat(initialVerdict(fixture, initialId, "REJECTED", "최초 반려 사유", "usr_admin").status()).isEqualTo(200);
        assertThat(initialVerdict(fixture, id(), "APPROVED", "변경", "usr_admin").status()).isEqualTo(409);
        assertThat(verdict(fixture, second, id(), "APPROVED", "변경", "admin").status()).isEqualTo(409);
        assertThat(current(fixture).getCurrentReviewId()).isEqualTo(third.data().path("reviewId").asText()); assertThat(snapshot()).isEqualTo(before);
        assertThat(reconsider(fixture, application, fixture.refund().getRefundId(), "변경 설명").status()).isEqualTo(409);
        assertThat(reconsider(fixture, id(), fixture.refund().getRefundId(), "추가 설명").status()).isEqualTo(409);
    }

    @Test void concurrentSameApplicationsConvergeAndDifferentApplicationsForOneRejectionHaveOneWinner() throws Exception {
        Fixture fixture = rejected(); String command = id(); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        List<Response> same = race(() -> reconsider(fixture, command, fixture.refund().getRefundId(), "추가 사유"),
                () -> reconsider(fixture, command, fixture.refund().getRefundId(), "추가 사유"));
        assertThat(same.stream().map(Response::status)).containsOnly(202); assertThat(same.get(0).data()).isEqualTo(same.get(1).data());
        Fixture other = rejected(); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        List<Response> different = race(() -> reconsider(other, id(), other.refund().getRefundId(), "첫 설명"),
                () -> reconsider(other, id(), other.refund().getRefundId(), "두번째 설명"));
        assertThat(different.stream().map(Response::status)).containsExactlyInAnyOrder(202, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round WHERE review_no=2", Integer.class)).isEqualTo(2);
    }

    @Test void concurrentOppositeVerdictsHaveOneImmutableWinnerAndAtMostOneSuspensionRequest() throws Exception {
        Fixture fixture = rejected(); grant.execute(grantInput(fixture)); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        String review = reconsider(fixture, id(), fixture.refund().getRefundId(), "추가 사유").data().path("reviewId").asText();
        when(clock.instant()).thenReturn(BASE.plusSeconds(2));
        List<Response> replies = race(() -> verdict(fixture, review, id(), "APPROVED", "승인", "admin-one"),
                () -> verdict(fixture, review, id(), "REJECTED", "반려", "admin-two"));
        assertThat(replies.stream().map(Response::status)).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(current(fixture).getStatus().name().equals("REJECTED") ? 0 : 1);
        assertThat(current(fixture).reviewReceipt()).isEqualTo(fixture.refund().reviewReceipt());
    }

    @Test void applicationAndCurrentPointerRollbackAfterActualInsertAndTheSameCommandCanRecover() {
        Fixture fixture = rejected(); when(clock.instant()).thenReturn(BASE.plusSeconds(1)); String command = id(); Map<String, List<Map<String, Object>>> before = snapshot();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            RefundReviewRound round = call.getArgument(0, RefundReviewRound.class); entityManager.persist(round); entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round", Integer.class)).isEqualTo(2);
            throw new IllegalStateException("after reconsideration insert and pointer flush");
        }).when(rounds).saveAndFlush(any(RefundReviewRound.class)));
        try { assertThat(reconsider(fixture, command, fixture.refund().getRefundId(), "추가 사유").status()).isEqualTo(500); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(rounds); }
        assertThat(reconsider(fixture, command, fixture.refund().getRefundId(), "추가 사유").status()).isEqualTo(202);
    }

    @Test void initialManualRefundAndItsFirstReviewRollbackTogetherBeforeAnyReceiptIsReturned() {
        Order paid = paid(); Map<String, List<Map<String, Object>>> before = snapshot();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            RefundReviewRound round = call.getArgument(0, RefundReviewRound.class); entityManager.persist(round); entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund", Integer.class)).isEqualTo(1);
            throw new IllegalStateException("after initial manual refund and review flush");
        }).when(rounds).save(any(RefundReviewRound.class)));
        String path = "/api/v1/orders/" + paid.getOrderNo() + "/refund";
        try { assertThat(http("POST", path, null, "usr_buyer", "MEMBER").status()).isEqualTo(500); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(rounds); }
        assertThat(http("POST", path, null, "usr_buyer", "MEMBER").status()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round", Integer.class)).isEqualTo(1);
    }

    @Test void approvalAuditAndSuspensionOutboxRollbackTogetherThenConfirmedSuspensionAndMoneyCompleteTheRefund() throws Exception {
        Fixture fixture = rejected(); LicenseGrant granted = grantInput(fixture); grant.execute(granted); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        String review = reconsider(fixture, id(), fixture.refund().getRefundId(), "추가 사유").data().path("reviewId").asText();
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); String command = id(); Map<String, List<Map<String, Object>>> before = snapshot();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call -> {
            call.callRealMethod(); entityManager.flush(); throw new IllegalStateException("after actual reconsideration approval and outbox flush");
        }).when(outbox).record(anyString(), anyString(), any(DomainEvent.class)));
        try { assertThat(verdict(fixture, review, command, "APPROVED", "승인", "admin").status()).isEqualTo(500); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(outbox); }
        Response accepted = verdict(fixture, review, command, "APPROVED", "승인", "admin"); assertThat(accepted.status()).isEqualTo(200);
        assertThat(verdict(fixture, review, command, "APPROVED", "승인", "admin").data()).isEqualTo(accepted.data());
        assertThat(attempts.count()).isZero(); Refund approved = current(fixture);
        LicenseSuspensionRequestedEvent expected = new LicenseSuspensionRequestedEvent(approved.getSuspensionRequestEventId(), BASE.plusSeconds(2), approved.getRefundId(),
                fixture.order().getOrderNo(), "usr_buyer", fixture.order().getProductId(), fixture.order().getProductCode(), fixture.order().getPaymentId());
        JsonNode payload = mapper.readTree(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id=?", String.class, expected.eventId()));
        assertThat(payload).isEqualTo(mapper.readTree(mapper.writeValueAsString(expected)));
        Map<String, Object> envelope = jdbc.queryForMap("SELECT aggregate_type,aggregate_id,event_type,topic,partition_key,status FROM outbox_event WHERE event_id=?", expected.eventId());
        assertThat(envelope).containsEntry("aggregate_type", "Order").containsEntry("aggregate_id", fixture.order().getOrderNo())
                .containsEntry("event_type", expected.eventType()).containsEntry("topic", expected.topic())
                .containsEntry("partition_key", fixture.order().getOrderNo()).containsEntry("status", "PENDING");
        when(clock.instant()).thenReturn(BASE.plusSeconds(4));
        suspension.execute(new LicenseSuspensionConfirmation(id(), BASE.plusSeconds(3), approved.getSuspensionRequestEventId(), approved.getRefundId(),
                granted.licenseId(), fixture.order().getOrderNo(), "usr_buyer", fixture.order().getProductId(), fixture.order().getProductCode(), fixture.order().getPaymentId()));
        RefundAttempt attempt = attempts.findByAttemptId(current(fixture).getCurrentAttemptId()).orElseThrow();
        payment.execute(new RefundPaymentConfirmation(id(), BASE.plusSeconds(5), approved.getRefundId(), attempt.getAttemptId(), attempt.getExecutionRequestEventId(),
                fixture.order().getOrderNo(), fixture.order().getPaymentId(), "usr_buyer", 10000, "KRW", "PG_RESPONSE", "pg-purchase", List.of("pg-cancel")));
        assertThat(customer(fixture).data().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(verdict(fixture, review, command, "APPROVED", "승인", "admin").data()).isEqualTo(accepted.data());
        assertThat(current(fixture).reviewReceipt()).isEqualTo(fixture.refund().reviewReceipt()); assertThat(attempts.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(2);
    }

    @Test void approvalBeforeGrantWaitsAndLateGrantStartsExactlyOneSuspension() {
        Fixture fixture = rejected(); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        String review = reconsider(fixture, id(), fixture.refund().getRefundId(), "추가 사유").data().path("reviewId").asText();
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); assertThat(verdict(fixture, review, id(), "APPROVED", "승인", "admin").status()).isEqualTo(200);
        assertThat(current(fixture).getStatus().name()).isEqualTo("PENDING_LICENSE_GRANT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
        LicenseGrant granted = grantInput(fixture); grant.execute(granted); grant.execute(granted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(current(fixture).getStatus().name()).isEqualTo("PENDING_LICENSE_SUSPENSION");
        assertThat(customer(fixture).data().path("reconsiderationAvailable").asBoolean()).isFalse();
    }

    @Test void anApprovedReconsiderationCanFailRestoreAndUseTheExistingCustomerExecutionRetry() {
        Fixture fixture = rejected(); LicenseGrant granted = grantInput(fixture); grant.execute(granted); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        String review = reconsider(fixture, id(), fixture.refund().getRefundId(), "추가 사유").data().path("reviewId").asText();
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); assertThat(verdict(fixture, review, id(), "APPROVED", "승인", "admin").status()).isEqualTo(200);
        Refund approved = current(fixture); when(clock.instant()).thenReturn(BASE.plusSeconds(4));
        suspension.execute(new LicenseSuspensionConfirmation(id(), BASE.plusSeconds(3), approved.getSuspensionRequestEventId(), approved.getRefundId(),
                granted.licenseId(), fixture.order().getOrderNo(), "usr_buyer", fixture.order().getProductId(), fixture.order().getProductCode(), fixture.order().getPaymentId()));
        RefundAttempt attempt = attempts.findByAttemptId(current(fixture).getCurrentAttemptId()).orElseThrow(); when(clock.instant()).thenReturn(BASE.plusSeconds(6));
        restore.execute(new RefundFailureConfirmation(id(), BASE.plusSeconds(5), approved.getRefundId(), attempt.getAttemptId(), attempt.getExecutionRequestEventId(),
                fixture.order().getOrderNo(), fixture.order().getPaymentId(), "usr_buyer", 10000, "KRW", "pg-original", "failed-cancel", "a".repeat(64)));
        RefundAttempt failed = attempts.findByAttemptId(attempt.getAttemptId()).orElseThrow();
        restore.execute(new LicenseRestorationConfirmation(id(), BASE.plusSeconds(7), failed.getRestorationRequestEventId(), approved.getRefundId(),
                failed.getAttemptId(), failed.getFailureEventId(), granted.licenseId(), fixture.order().getOrderNo(), "usr_buyer", fixture.order().getProductId(),
                fixture.order().getProductCode(), fixture.order().getPaymentId(), failed.getSuspensionRequestEventId(), failed.getSuspensionEventId()));
        when(clock.instant()).thenReturn(BASE.plusSeconds(8)); assertThat(customer(fixture).data().path("status").asText()).isEqualTo("FAILED");
        assertThat(customer(fixture).data().path("reconsiderationAvailable").asBoolean()).isFalse();
        assertThat(http("POST", path(fixture) + "/retries", Map.of("previousAttemptId", attempt.getAttemptId()), "usr_buyer", "MEMBER").status()).isEqualTo(202);
        assertThat(current(fixture).getCurrentReviewId()).isEqualTo(review); assertThat(current(fixture).getReceivedAt()).isEqualTo(BASE);
        assertThat(current(fixture).isAutomaticRefundEligible()).isFalse(); assertThat(current(fixture).reviewReceipt()).isEqualTo(fixture.refund().reviewReceipt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isEqualTo(4);
    }

    @Test void globalApplicationIdCollisionHasOneWinnerAndTheLoserRetainsItsRejection() throws Exception {
        Fixture first = rejected(); Fixture second = rejected(); String command = id(); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        CountDownLatch reads = new CountDownLatch(2);
        doAnswer(call -> {
            Optional<RefundReviewRound> result = entityManager.createQuery("select r from RefundReviewRound r where r.applicationRequestId=:id", RefundReviewRound.class)
                    .setParameter("id", command).getResultList().stream().findFirst(); reads.countDown(); await(reads); return result;
        }).when(rounds).findByApplicationRequestId(command);
        try {
            List<Response> replies = race(() -> reconsider(first, command, first.refund().getRefundId(), "설명"), () -> reconsider(second, command, second.refund().getRefundId(), "설명"));
            assertThat(replies.stream().map(Response::status)).containsExactlyInAnyOrder(202, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round WHERE application_request_id=?", Integer.class, command)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund WHERE status='REJECTED'", Integer.class)).isEqualTo(1);
        } finally { reset(rounds); }
    }

    @Test void administratorDecisionIdsAreUniqueAcrossOriginalAndReconsiderationRounds() throws Exception {
        Fixture pending = pending(); Fixture rejected = rejected(); when(clock.instant()).thenReturn(BASE.plusSeconds(1));
        String review = reconsider(rejected, id(), rejected.refund().getRefundId(), "추가 설명").data().path("reviewId").asText();
        String command = id(); CountDownLatch reads = new CountDownLatch(2); when(clock.instant()).thenReturn(BASE.plusSeconds(2));
        doAnswer(call -> {
            Optional<RefundReviewRound> result = entityManager.createQuery("select r from RefundReviewRound r where r.decisionRequestId=:id", RefundReviewRound.class)
                    .setParameter("id", command).getResultList().stream().findFirst(); reads.countDown(); await(reads); return result;
        }).when(rounds).findByDecisionRequestId(command);
        try {
            List<Response> replies = race(() -> initialVerdict(pending, command, "REJECTED", "최초", "admin"),
                    () -> verdict(rejected, review, command, "REJECTED", "재심사", "admin"));
            assertThat(replies.stream().map(Response::status)).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round WHERE decision_request_id=?", Integer.class, command)).isEqualTo(1);
        } finally { reset(rounds); }
    }

    private Order paid() {
        String code = "Game-" + id();
        Order order = Order.create("usr_buyer", new OrderRequest(id(), code, 3, 10000),
                new PublishedProduct(PRODUCTS.incrementAndGet(), code, "재심사 검증 상품", 3, 10000, "KRW"), id());
        ReflectionTestUtils.setField(order, "createdAt", BASE.minusSeconds(21600));
        order.apply(new PaymentResult(id(), BASE.minusSeconds(18000), order.getOrderNo(), order.getRequestEventId(), id(),
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
