package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.SECRET;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.test.PausePoints;
import com.stove.common.test.PausePoints.Gate;
import com.stove.common.test.PausePoints.Run;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.payment.core.domain.ApprovalClosureReceipt;
import com.stove.payment.core.domain.ApprovalClosureRequest;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.OrderExpiryInput;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.service.ApplyOrderExpiry;
import com.stove.payment.core.service.CloseApprovalReview;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.service.ResolvePaymentApproval;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * Decision 115: an operator closes a held approval only on PG evidence re-inspected at closure time. The closure takes the
 * worker's lease, never moves money, and ends the purchase as FAILED (Order FAILED) or, when the order already expired, EXPIRED.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stove.payment.pg.provider=PORTONE_TEST", "stove.payment.processing-enabled=false",
        "stove.outbox.relay-enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class})
class ApprovalReviewClosureIntegrationTest {
    static final PausePoints POINTS = new PausePoints();
    @TestConfiguration static class Pausing {
        @Bean static BeanPostProcessor pausing() {
            return PausePoints.pausing(POINTS, PaymentApprovalRepository.class, PaymentRepository.class);
        }
    }

    private static final String ROOT = "/api/v1/admin/approval-reviews";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AtomicReference<Instant> TIME = new AtomicReference<>();
    private static final Map<String, Payment> PURCHASES = new ConcurrentHashMap<>();
    private static final Map<String, String> ATTEMPTS = new ConcurrentHashMap<>();
    private static final Map<String, String> STATES = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> GETS = new ConcurrentHashMap<>();
    private static final Map<String, JsonNode> RECEIPTS = new ConcurrentHashMap<>();
    private static final Map<String, JsonNode> FAILURE_NOTICES = new ConcurrentHashMap<>();
    private static final List<String> POSTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean PG_DOWN = new AtomicBoolean();
    private static final AtomicBoolean REJECT = new AtomicBoolean();
    private static final AtomicBoolean WRONG_AMOUNT = new AtomicBoolean();
    private static final AtomicBoolean FIRST_UNAVAILABLE = new AtomicBoolean();
    private static final HttpServer PG;
    private static final HttpServer ORDER;
    static {
        try {
            PG = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PG.createContext("/payments/", ApprovalReviewClosureIntegrationTest::pg);
            PG.setExecutor(Executors.newCachedThreadPool()); PG.start();
            ORDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ORDER.createContext("/internal/v1/orders/", ApprovalReviewClosureIntegrationTest::order);
            ORDER.setExecutor(Executors.newCachedThreadPool()); ORDER.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { PG.stop(0); ORDER.stop(0); }));
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("stove.payment.portone.base-url", () -> "http://127.0.0.1:" + PG.getAddress().getPort());
        registry.add("stove.payment.portone.api-secret", () -> "fixture-api-secret");
        registry.add("stove.payment.portone.store-id", () -> STORE); registry.add("stove.payment.portone.channel-key", () -> CHANNEL);
        registry.add("stove.payment.portone.webhook-secret", () -> SECRET);
        registry.add("stove.payment.portone.webhook-url", () -> "https://payment-test.example/api/v1/payments/webhooks/portone");
        registry.add("stove.payment.approval.order-base-url", () -> "http://127.0.0.1:" + ORDER.getAddress().getPort());
        registry.add("stove.payment.approval.order-service-key", () -> "fixture-order-service-key");
        registry.add("stove.payment.approval.payload-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> PaymentIntegrationTestBase.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + PaymentIntegrationTestBase.JWKS.getAddress().getPort() + "/jwks");
    }
    @Autowired ReceiveOrderPayment receive;
    @Autowired ApplyOrderExpiry expire;
    @Autowired ResolvePaymentApproval worker;
    @Autowired ResolvePayment resolve;
    @Autowired CloseApprovalReview close;
    @Autowired PaymentRepository payments;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired PrometheusMeterRegistry prometheus;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @LocalServerPort int port;

    @BeforeEach void initialize() { clean(); when(clock.instant()).thenAnswer(call -> TIME.get()); }
    @AfterEach void clean() {
        POINTS.reset();
        // The closure (V37) and investigation (V38) audits are not cascaded from their approval,
        // so they are removed before the purchases they refer to.
        jdbc.update("DELETE FROM approval_closure");
        jdbc.update("DELETE FROM approval_investigation");
        jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
        PURCHASES.clear(); ATTEMPTS.clear(); STATES.clear(); GETS.clear(); RECEIPTS.clear(); FAILURE_NOTICES.clear(); POSTS.clear();
        PG_DOWN.set(false); REJECT.set(false); WRONG_AMOUNT.set(false); FIRST_UNAVAILABLE.set(false);
    }

    @Test void eachClosableEvidenceEndsAnAdmittedHoldAndFailsTheProcessingOrder() throws Exception {
        for (String evidence : List.of("CANCELLED", "FAILED", "READY")) {
            Payment payment = purchase();
            PaymentApproval held = heldAfterAdmission(payment);
            STATES.put(payment.getPaymentId(), evidence);
            double gauge = gauge("PG_REQUEST_REJECTED");
            Reply closed = closure(held, id(), "PG 콘솔에서 " + evidence + " 확인", "ADMIN");
            assertThat(closed.status()).as(evidence).isEqualTo(200);
            assertClosed(payment, held, evidence, closed, PaymentStatus.FAILED);
            assertFailurePublished(payment, evidence);
            assertThat(gauge("PG_REQUEST_REJECTED")).isEqualTo(gauge - 1);
            assertNoFurtherProviderTrafficOrPayment(payment, held);
        }
    }

    @Test void eachClosableEvidenceFailsAnUnadmittedHoldWhoseOrderHasNotExpired() throws Exception {
        for (String evidence : List.of("CANCELLED", "FAILED", "READY")) {
            Payment payment = purchase();
            PaymentApproval held = "CANCELLED".equals(evidence) ? paidWithoutAdmission(payment) : invalidBeforeAdmission(payment);
            STATES.put(payment.getPaymentId(), evidence);
            double gauge = gauge(held.getLastErrorCode());
            Reply closed = closure(held, id(), "주문 연결 없는 결제 확인 후 종결", "ADMIN");
            assertThat(closed.status()).as(evidence).isEqualTo(200);
            assertClosed(payment, held, evidence, closed, PaymentStatus.FAILED);
            assertFailurePublished(payment, evidence);
            assertThat(gauge(held.getLastErrorCode())).isEqualTo(gauge - 1);
            assertNoFurtherProviderTrafficOrPayment(payment, held);
        }
    }

    @Test void eachClosableEvidenceExpiresAnUnadmittedHoldWhoseOrderAlreadyExpiredWithoutAnEvent() throws Exception {
        for (String evidence : List.of("CANCELLED", "FAILED", "READY")) {
            Payment payment = purchase();
            PaymentApproval held = "CANCELLED".equals(evidence) ? paidWithoutAdmission(payment) : invalidBeforeAdmission(payment);
            recordExpiry(payment);
            assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
            STATES.put(payment.getPaymentId(), evidence);
            double gauge = gauge(held.getLastErrorCode());
            Reply closed = closure(held, id(), "만료 주문의 보류 종결", "ADMIN");
            assertThat(closed.status()).as(evidence).isEqualTo(200);
            assertClosed(payment, held, evidence, closed, PaymentStatus.EXPIRED);
            assertThat(outbox(payment)).isEmpty();
            assertThat(gauge(held.getLastErrorCode())).isEqualTo(gauge - 1);
            assertNoFurtherProviderTrafficOrPayment(payment, held);
        }
    }

    @Test void paidOrPendingEvidenceIsRefusedAndOnlyBecomesTheLatestObservation() {
        for (String state : List.of("PAID", "PENDING", "PAY_PENDING")) {
            Payment payment = purchase();
            PaymentApproval held = invalidBeforeAdmission(payment);
            STATES.put(payment.getPaymentId(), state);
            Instant next = row(held).getNextAt();
            Reply refused = closure(held, id(), "아직 종결할 수 없음", "ADMIN");
            assertThat(refused.status()).as(state).isEqualTo(409);
            assertThat(refused.body().path("error").path("code").asText()).isEqualTo("PAID".equals(state) ? "CLOSURE_PG_PAID" : "CLOSURE_PG_PENDING");
            PaymentApproval current = row(held);
            assertThat(current.getLatestPgState()).isEqualTo("PAID".equals(state) ? ApprovalObservation.State.PAID : ApprovalObservation.State.PENDING);
            assertThat(current.getLastInspectedAt()).isEqualTo(TIME.get());
            assertThat(current.getLastInspectionError()).isNull();
            assertStillHeld(payment, held, next);
        }
    }

    @Test void invalidOrUnavailableEvidenceIsRefusedAndRecordedAsTheInspectionError() {
        for (String fault : List.of("WRONG_AMOUNT", "PARTIAL_CANCEL", "PG_DOWN")) {
            Payment payment = purchase();
            PaymentApproval held = paidWithoutAdmission(payment);
            STATES.put(payment.getPaymentId(), "PARTIAL_CANCEL".equals(fault) ? "PARTIAL" : "CANCELLED");
            WRONG_AMOUNT.set("WRONG_AMOUNT".equals(fault)); PG_DOWN.set("PG_DOWN".equals(fault));
            Instant next = row(held).getNextAt();
            Reply refused = closure(held, id(), "근거 확인 실패", "ADMIN");
            WRONG_AMOUNT.set(false); PG_DOWN.set(false);
            assertThat(refused.status()).as(fault).isEqualTo(409);
            assertThat(refused.body().path("error").path("code").asText()).isEqualTo("PG_DOWN".equals(fault) ? "CLOSURE_PG_UNAVAILABLE" : "CLOSURE_PG_INVALID_EVIDENCE");
            PaymentApproval current = row(held);
            assertThat(current.getLastInspectionError()).isEqualTo("PG_DOWN".equals(fault) ? "UNAVAILABLE" : "INVALID_EVIDENCE");
            assertThat(current.getLastInspectedAt()).isEqualTo(TIME.get());
            assertThat(current.getLatestPgState()).isEqualTo(ApprovalObservation.State.PAID);
            assertStillHeld(payment, held, next);
        }
    }

    @Test void onlyACurrentlyHeldApprovalCanBeClosed() {
        Payment payment = purchase(); String command = id(); submit(payment, command);
        PaymentApproval requested = approvals.findByApprovalId(command).orElseThrow();
        Reply notHeld = closure(requested, id(), "보류 아님", "ADMIN");
        assertThat(notHeld.status()).isEqualTo(409);
        assertThat(notHeld.body().path("error").path("code").asText()).isEqualTo("APPROVAL_NOT_HELD");
        assertThat(gets(payment)).isZero();
        assertThat(row(requested).getStatus()).isEqualTo(PaymentApprovalStatus.REQUESTED);

        Payment other = purchase(); PaymentApproval held = paidWithoutAdmission(other); STATES.put(other.getPaymentId(), "CANCELLED");
        assertThat(closure(held, id(), "종결", "ADMIN").status()).isEqualTo(200);
        int before = gets(other);
        Reply again = closure(held, id(), "다시 종결", "ADMIN");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("error").path("code").asText()).isEqualTo("APPROVAL_NOT_HELD");
        assertThat(gets(other)).isEqualTo(before);
        assertThat(http("POST", ROOT + "/" + id() + "/closure", Map.of("requestId", id(), "note", "없음"), token("ADMIN")).status()).isEqualTo(404);
    }

    @Test void theSameCommandReturnsTheOriginalResultAndAChangedCommandConflicts() {
        Payment payment = purchase(); PaymentApproval held = paidWithoutAdmission(payment); STATES.put(payment.getPaymentId(), "CANCELLED");
        String command = id();
        Reply first = closure(held, command, "콘솔 취소 확인", "ADMIN");
        assertThat(first.status()).isEqualTo(200);
        int before = gets(payment);
        Reply replay = closure(held, command, "콘솔 취소 확인", "ADMIN");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.cache()).isEqualTo("no-store");
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(gets(payment)).isEqualTo(before);
        Reply changedNote = closure(held, command, "다른 이유", "ADMIN");
        assertThat(changedNote.status()).isEqualTo(409);
        assertThat(changedNote.body().path("error").path("code").asText()).isEqualTo("APPROVAL_CLOSURE_CHANGED");
        Reply changedActor = http("POST", ROOT + "/" + held.getApprovalId() + "/closure", Map.of("requestId", command, "note", "콘솔 취소 확인"),
                PaymentIntegrationTestBase.token("usr_other_operator", "ADMIN"));
        assertThat(changedActor.status()).isEqualTo(409);
        Payment second = purchase(); PaymentApproval otherHold = paidWithoutAdmission(second); STATES.put(second.getPaymentId(), "CANCELLED");
        int otherReads = gets(second);
        Reply reused = closure(otherHold, command, "콘솔 취소 확인", "ADMIN");
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.body().path("error").path("code").asText()).isEqualTo("APPROVAL_CLOSURE_CHANGED");
        assertThat(row(otherHold).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(gets(second)).isEqualTo(otherReads);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_closure", Integer.class)).isEqualTo(1);
        assertThat(outbox(payment)).hasSize(1);
    }

    @Test void onlyAnAdministratorCanCloseAndMalformedCommandsNeverReachThePg() {
        Payment payment = purchase(); PaymentApproval held = paidWithoutAdmission(payment); STATES.put(payment.getPaymentId(), "CANCELLED");
        int before = gets(payment);
        String path = ROOT + "/" + held.getApprovalId() + "/closure";
        assertThat(http("POST", path, Map.of("requestId", id(), "note", "종결"), null).status()).isEqualTo(401);
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            assertThat(http("POST", path, Map.of("requestId", id(), "note", "종결"), token(role)).status()).as(role).isEqualTo(403);
        for (Map<String, String> body : List.of(Map.of("requestId", "not-a-uuid", "note", "종결"), Map.of("requestId", id(), "note", " "),
                Map.of("requestId", id(), "note", "x".repeat(501)), Map.of("note", "종결")))
            assertThat(http("POST", path, body, token("ADMIN")).status()).isEqualTo(400);
        assertThat(http("POST", ROOT + "/not-a-uuid/closure", Map.of("requestId", id(), "note", "종결"), token("ADMIN")).status()).isEqualTo(400);
        assertThat(gets(payment)).isEqualTo(before);
        assertThat(row(held).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_closure", Integer.class)).isZero();
    }

    @Test void aCancelledPgPaymentStopsAnUnheldApprovalForReviewAndNeverClosesItAlone() {
        double gauge = gauge("PG_CANCELLED");
        Payment payment = purchase(); String command = id(); submit(payment, command);
        STATES.put(payment.getPaymentId(), "CANCELLED");
        worker.execute(command);
        PaymentApproval stopped = approvals.findByApprovalId(command).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stopped.getLastErrorCode()).isEqualTo("PG_CANCELLED");
        assertThat(stopped.getLatestPgState()).isEqualTo(ApprovalObservation.State.CANCELLED);
        assertThat(stopped.isPostStopped()).isTrue(); assertThat(stopped.getAdmittedAt()).isNull();
        assertThat(gauge("PG_CANCELLED")).isEqualTo(gauge + 1);
        advance(5); worker.execute(command);
        assertThat(row(stopped).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(RECEIPTS).isEmpty(); assertThat(posts(payment)).isZero(); assertThat(outbox(payment)).isEmpty();

        Payment admittedPurchase = purchase(); String admitted = id(); submit(admittedPurchase, admitted);
        FIRST_UNAVAILABLE.set(true); worker.execute(admitted);
        assertThat(approvals.findByApprovalId(admitted).orElseThrow().getStatus()).isEqualTo(PaymentApprovalStatus.ADMITTED);
        STATES.put(admittedPurchase.getPaymentId(), "CANCELLED"); advance(5); worker.execute(admitted);
        PaymentApproval admittedStop = approvals.findByApprovalId(admitted).orElseThrow();
        assertThat(admittedStop.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(admittedStop.getLastErrorCode()).isEqualTo("PG_CANCELLED");
        assertThat(admittedStop.getAdmittedAt()).isNotNull();
        advance(5); worker.execute(admitted);
        assertThat(posts(admittedPurchase)).isEqualTo(1);
        assertThat(FAILURE_NOTICES).isEmpty();
        assertThat(stored(admittedPurchase).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(outbox(admittedPurchase)).isEmpty();
        assertThat(gauge("PG_CANCELLED")).isEqualTo(gauge + 2);
    }

    @Test void aCancelledObservationOfAnAlreadyHeldApprovalIsOnlyRecorded() {
        Payment unadmitted = purchase(); PaymentApproval paid = paidWithoutAdmission(unadmitted);
        Payment admitted = purchase(); PaymentApproval rejected = heldAfterAdmission(admitted);
        int sent = POSTS.size();
        STATES.put(unadmitted.getPaymentId(), "CANCELLED"); STATES.put(admitted.getPaymentId(), "CANCELLED");
        advance(5); worker.execute(paid.getApprovalId()); worker.execute(rejected.getApprovalId());
        for (PaymentApproval held : List.of(paid, rejected)) {
            PaymentApproval current = row(held);
            assertThat(current.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
            assertThat(current.getLastErrorCode()).isEqualTo(held.getLastErrorCode());
            assertThat(current.getReviewStartedAt()).isEqualTo(held.getReviewStartedAt());
            assertThat(current.getLatestPgState()).isEqualTo(ApprovalObservation.State.CANCELLED);
            assertThat(current.getProtectedPayload()).isNotNull();
        }
        assertThat(POSTS).hasSize(sent); assertThat(FAILURE_NOTICES).isEmpty();
        assertThat(stored(unadmitted).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(stored(admitted).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox(unadmitted)).isEmpty(); assertThat(outbox(admitted)).isEmpty();
    }

    /** Decision 103 (i): a worker cycle holds the lease, so the closure must retry later and leaves nothing behind. */
    @Test void aWorkerCycleHoldingTheLeaseMakesTheClosureRetryLaterWithoutAnyEffect() throws Exception {
        Payment payment = purchase(); PaymentApproval held = paidWithoutAdmission(payment);
        STATES.put(payment.getPaymentId(), "CANCELLED"); advance(5);
        Gate claimed = POINTS.arm("PaymentRepository.findByPaymentId", "worker");
        Run<Boolean> cycle = PausePoints.start("worker", () -> { worker.execute(held.getApprovalId()); return true; });
        claimed.awaitArrival();
        assertThat(row(held).getLeaseToken()).isNotNull();
        Reply busy = closure(held, id(), "작업자 진행 중", "ADMIN");
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.body().path("error").path("code").asText()).isEqualTo("APPROVAL_CLOSURE_BUSY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_closure", Integer.class)).isZero();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(outbox(payment)).isEmpty();
        claimed.release();
        assertThat(cycle.join()).isTrue();
        PaymentApproval recorded = row(held);
        assertThat(recorded.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(recorded.getLatestPgState()).isEqualTo(ApprovalObservation.State.CANCELLED);
        assertThat(recorded.getLeaseToken()).isNull();
        Reply closed = closure(held, id(), "작업자 종료 후 종결", "ADMIN");
        assertThat(closed.status()).isEqualTo(200);
        assertThat(outbox(payment)).hasSize(1);
        assertThat(POINTS.reset()).isEmpty();
    }

    /** Decision 103 (i): the closure holds the lease between its PG read and its lock, so the worker cycle has no effect at all. */
    @Test void aClosureHoldingTheLeaseKeepsTheWorkerOutAndEndsThePurchaseExactlyOnce() throws Exception {
        Payment payment = purchase(); PaymentApproval held = heldAfterAdmission(payment);
        STATES.put(payment.getPaymentId(), "FAILED"); advance(5);
        Gate beforeClaim = POINTS.arm("PaymentApprovalRepository.findPaymentIdByApprovalId", "closure");
        Run<ApprovalClosureReceipt> closing = PausePoints.start("closure", () ->
                close.execute(new ApprovalClosureRequest(id(), held.getApprovalId(), "usr_operator", "PG 실패 확인")));
        beforeClaim.awaitArrival();
        Gate beforeLock = POINTS.arm("PaymentApprovalRepository.findPaymentIdByApprovalId", "closure");
        beforeClaim.release();
        beforeLock.awaitArrival();
        int read = gets(payment); int sent = POSTS.size();
        worker.execute(held.getApprovalId());
        assertThat(gets(payment)).isEqualTo(read);
        assertThat(POSTS).hasSize(sent);
        assertThat(FAILURE_NOTICES).isEmpty();
        assertThat(row(held).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        beforeLock.release();
        ApprovalClosureReceipt receipt = closing.join();
        assertThat(receipt.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(receipt.approvalStatus()).isEqualTo(PaymentApprovalStatus.FAILED);
        assertFailurePublished(payment, "FAILED");
        advance(5); worker.execute(held.getApprovalId());
        assertThat(FAILURE_NOTICES).isEmpty(); assertThat(outbox(payment)).hasSize(1);
        assertThat(POINTS.reset()).isEmpty();
    }

    /** Decision 103 (ii): Order expiry reaches Payment after the closure read the PG but before it locked; the purchase expires, no failure is published. */
    @Test void anOrderExpiryCommittedWhileTheClosureInspectsEndsThePurchaseAsExpiredWithoutAFailureEvent() throws Exception {
        Payment payment = purchase(); PaymentApproval held = paidWithoutAdmission(payment);
        STATES.put(payment.getPaymentId(), "CANCELLED");
        Gate beforeClaim = POINTS.arm("PaymentApprovalRepository.findPaymentIdByApprovalId", "closure");
        Run<ApprovalClosureReceipt> closing = PausePoints.start("closure", () ->
                close.execute(new ApprovalClosureRequest(id(), held.getApprovalId(), "usr_operator", "콘솔 취소 확인")));
        beforeClaim.awaitArrival();
        Gate beforeLock = POINTS.arm("PaymentApprovalRepository.findPaymentIdByApprovalId", "closure");
        beforeClaim.release();
        beforeLock.awaitArrival();
        recordExpiry(payment);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        beforeLock.release();
        ApprovalClosureReceipt receipt = closing.join();
        assertThat(receipt.paymentStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(stored(payment).getResultEventId()).isNull();
        assertThat(row(held).getStatus()).isEqualTo(PaymentApprovalStatus.FAILED);
        assertThat(outbox(payment)).isEmpty();
        assertThat(POINTS.reset()).isEmpty();
    }

    /** Decision 103 (ii), the other order: the failure is committed first, the late expiry keeps it and only retains the notice. */
    @Test void aClosureCommittedBeforeTheOrderExpiryKeepsTheFailedPurchaseAndRetainsTheExpiryNotice() throws Exception {
        Payment payment = purchase(); PaymentApproval held = paidWithoutAdmission(payment);
        STATES.put(payment.getPaymentId(), "CANCELLED");
        assertThat(closure(held, id(), "콘솔 취소 확인", "ADMIN").status()).isEqualTo(200);
        recordExpiry(payment);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertFailurePublished(payment, "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT financial_conflict FROM payment_order_lifecycle WHERE order_no=?", Boolean.class, payment.getOrderNo())).isTrue();
    }

    /** The append-only closure record is audit evidence: deleting its approval or purchase must not silently erase it. */
    @Test void theClosureAuditCannotBeErasedByDeletingItsApprovalOrPurchase() {
        Payment payment = purchase(); PaymentApproval held = paidWithoutAdmission(payment); STATES.put(payment.getPaymentId(), "CANCELLED");
        assertThat(closure(held, id(), "콘솔 취소 확인", "ADMIN").status()).isEqualTo(200);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment_approval WHERE approval_id=?", held.getApprovalId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM purchase_payment WHERE payment_id=?", payment.getPaymentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_closure WHERE approval_id=?", Integer.class, held.getApprovalId())).isEqualTo(1);
        assertThat(row(held).getStatus()).isEqualTo(PaymentApprovalStatus.FAILED);
    }

    private void assertClosed(Payment payment, PaymentApproval held, String evidence, Reply closed, PaymentStatus outcome) {
        assertThat(closed.cache()).isEqualTo("no-store");
        assertThat(closed.location()).isEqualTo(ROOT + "/" + held.getApprovalId());
        JsonNode data = closed.body().path("data");
        assertThat(data.path("approvalId").asText()).isEqualTo(held.getApprovalId());
        assertThat(data.path("orderNo").asText()).isEqualTo(payment.getOrderNo());
        assertThat(data.path("approvalStatus").asText()).isEqualTo("FAILED");
        assertThat(data.path("paymentStatus").asText()).isEqualTo(outcome.name());
        assertThat(data.path("closure").path("operatorSubject").asText()).isEqualTo("usr_operator");
        assertThat(data.path("closure").path("evidenceState").asText()).isEqualTo(evidence);
        assertThat(data.path("closure").path("evidenceObservedAt").asText()).isEqualTo(TIME.get().toString());
        assertThat(closed.body().toString()).doesNotContain("private-token", "protectedPayload", "v1:", "ciphertext", ATTEMPTS.get(payment.getPaymentId()));
        PaymentApproval approval = row(held);
        assertThat(approval.getStatus()).isEqualTo(PaymentApprovalStatus.FAILED);
        assertThat(approval.getLastErrorCode()).isEqualTo(held.getLastErrorCode());
        assertThat(approval.getNextAt()).isNull(); assertThat(approval.getLeaseToken()).isNull();
        assertThat(approval.getProtectedPayload()).isNull(); assertThat(approval.getResolvedAt()).isEqualTo(TIME.get());
        assertThat(approval.getLatestPgState().name()).isEqualTo(evidence);
        Payment ended = stored(payment);
        assertThat(ended.getStatus()).isEqualTo(outcome);
        assertThat(ended.getNextAttemptAt()).isNull(); assertThat(ended.getNextFailureLookupAt()).isNull();
        for (String view : List.of(ROOT + "/" + held.getApprovalId(), ROOT + "/" + held.getApprovalId() + "/investigations")) {
            Reply read = http("GET", view, null, token("ADMIN"));
            assertThat(read.status()).isEqualTo(200); assertThat(read.cache()).isEqualTo("no-store");
            JsonNode record = read.body().path("data").path("closure");
            assertThat(record.path("requestId").asText()).isEqualTo(data.path("closure").path("requestId").asText());
            assertThat(record.path("note").asText()).isEqualTo(data.path("closure").path("note").asText());
            assertThat(record.path("evidenceState").asText()).isEqualTo(evidence);
            assertThat(record.path("closedAt").asText()).isEqualTo(TIME.get().toString());
        }
        JsonNode detail = http("GET", ROOT + "/" + held.getApprovalId(), null, token("ADMIN")).body().path("data");
        assertThat(detail.path("status").asText()).isEqualTo("FAILED");
        assertThat(detail.path("reason").asText()).isEqualTo(held.getLastErrorCode());
        assertThat(http("GET", "/api/v1/payments/" + payment.getOrderNo(), null, member(payment)).body().path("data").path("status").asText()).isEqualTo(outcome.name());
    }

    private void assertFailurePublished(Payment payment, String evidence) throws Exception {
        List<Map<String, Object>> rows = outbox(payment);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        Payment failed = stored(payment);
        assertThat(row.get("event_type")).isEqualTo("OrderPaymentFailed");
        assertThat(row.get("topic")).isEqualTo("stove.payment.v1");
        assertThat(row.get("partition_key")).isEqualTo(payment.getOrderNo());
        assertThat(row.get("event_id")).isEqualTo(failed.getResultEventId());
        JsonNode event = JSON.readTree((String) row.get("payload"));
        assertThat(event.path("eventId").asText()).isEqualTo(failed.getResultEventId());
        assertThat(event.path("orderNo").asText()).isEqualTo(payment.getOrderNo());
        assertThat(event.path("requestEventId").asText()).isEqualTo(payment.getRequestEventId());
        assertThat(event.path("paymentId").asText()).isEqualTo(payment.getPaymentId());
        assertThat(event.path("buyerSubject").asText()).isEqualTo(payment.getBuyerSubject());
        assertThat(event.path("amount").asLong()).isEqualTo(payment.getAmount());
        assertThat(event.path("currency").asText()).isEqualTo(payment.getCurrency());
        assertThat(event.path("reasonCode").asText()).isEqualTo("REVIEW_CLOSED");
        assertThat(event.path("reason").asText()).contains(evidence).hasSizeLessThanOrEqualTo(500);
        assertThat(failed.getFailureReasonCode()).isEqualTo("REVIEW_CLOSED");
        assertThat(failed.getProviderTransactionId()).isEqualTo(ATTEMPTS.get(payment.getPaymentId()));
    }

    private void assertNoFurtherProviderTrafficOrPayment(Payment payment, PaymentApproval held) {
        int read = gets(payment); int sent = posts(payment); int events = outbox(payment).size();
        advance(3600);
        worker.execute(); worker.execute(held.getApprovalId()); resolve.execute(payment.getPaymentId());
        assertThat(gets(payment)).isEqualTo(read); assertThat(posts(payment)).isEqualTo(sent);
        assertThat(outbox(payment)).hasSize(events);
        Reply checkout = http("GET", "/api/v1/payments/" + payment.getOrderNo() + "/checkout", null, member(payment));
        assertThat(checkout.status()).isEqualTo(409);
        ATTEMPTS.put(payment.getPaymentId(), "tx-" + id());
        assertThat(submit(payment, id()).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_approval WHERE payment_id=?", Integer.class, payment.getPaymentId())).isEqualTo(1);
        assertThat(gets(payment)).isEqualTo(read); assertThat(posts(payment)).isEqualTo(sent);
    }

    private void assertStillHeld(Payment payment, PaymentApproval held, Instant next) {
        PaymentApproval current = row(held);
        assertThat(current.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(current.getLastErrorCode()).isEqualTo(held.getLastErrorCode());
        assertThat(current.getLeaseToken()).isNull(); assertThat(current.getLeaseUntil()).isNull();
        assertThat(current.getNextAt()).isEqualTo(next);
        assertThat(current.getProtectedPayload()).isNotNull();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox(payment)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_closure WHERE approval_id=?", Integer.class, held.getApprovalId())).isZero();
    }

    private PaymentApproval paidWithoutAdmission(Payment payment) {
        String command = id(); assertThat(submit(payment, command).status()).isEqualTo(202);
        STATES.put(payment.getPaymentId(), "PAID"); worker.execute(command);
        PaymentApproval held = approvals.findByApprovalId(command).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(held.getLastErrorCode()).isEqualTo("PAID_WITHOUT_ADMISSION"); assertThat(held.getAdmittedAt()).isNull();
        return held;
    }
    private PaymentApproval invalidBeforeAdmission(Payment payment) {
        String command = id(); assertThat(submit(payment, command).status()).isEqualTo(202);
        WRONG_AMOUNT.set(true); worker.execute(command); WRONG_AMOUNT.set(false);
        PaymentApproval held = approvals.findByApprovalId(command).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(held.getLastErrorCode()).isEqualTo("PG_INVALID_EVIDENCE"); assertThat(held.getAdmittedAt()).isNull();
        return held;
    }
    private PaymentApproval heldAfterAdmission(Payment payment) {
        String command = id(); assertThat(submit(payment, command).status()).isEqualTo(202);
        REJECT.set(true); worker.execute(command); REJECT.set(false);
        PaymentApproval held = approvals.findByApprovalId(command).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(held.getLastErrorCode()).isEqualTo("PG_REQUEST_REJECTED"); assertThat(held.getAdmittedAt()).isNotNull();
        assertThat(posts(payment)).isEqualTo(1);
        return held;
    }
    private void recordExpiry(Payment payment) {
        expire.execute(new OrderExpiryInput(id(), payment.getOrderOccurredAt().plusSeconds(1801), payment.getOrderNo(), payment.getRequestEventId(),
                payment.getBuyerSubject(), payment.getAmount(), payment.getCurrency(), payment.getOrderOccurredAt().plusSeconds(1800)));
    }
    private Payment purchase() {
        Payment payment = receive.execute(request()); TIME.set(payment.getCreatedAt().plusSeconds(1));
        PURCHASES.put(payment.getPaymentId(), payment); ATTEMPTS.put(payment.getPaymentId(), "tx-" + id()); STATES.put(payment.getPaymentId(), "READY");
        return payment;
    }
    private double gauge(String reason) {
        String line = prometheus.scrape().lines().filter(l -> l.startsWith("stove_payment_approval_review_required{") && l.contains("reason=\"" + reason + "\""))
                .findFirst().orElseThrow(() -> new AssertionError("No review gauge for " + reason));
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }
    private List<Map<String, Object>> outbox(Payment payment) {
        return jdbc.queryForList("SELECT event_id, event_type, topic, partition_key, payload FROM outbox_event WHERE aggregate_id=?", payment.getOrderNo());
    }
    private Payment stored(Payment payment) { return payments.findById(payment.getId()).orElseThrow(); }
    private PaymentApproval row(PaymentApproval approval) { return approvals.findByApprovalId(approval.getApprovalId()).orElseThrow(); }
    private int gets(Payment payment) { return GETS.computeIfAbsent(payment.getPaymentId(), ignored -> new AtomicInteger()).get(); }
    private int posts(Payment payment) { return (int) POSTS.stream().filter(payment.getPaymentId()::equals).count(); }
    private void advance(int seconds) { TIME.set(TIME.get().plusSeconds(seconds)); }
    private String token(String role) { return PaymentIntegrationTestBase.token("usr_operator", role); }
    private String member(Payment payment) { return PaymentIntegrationTestBase.token(payment.getBuyerSubject(), "MEMBER"); }
    private Reply submit(Payment payment, String command) {
        return http("POST", "/api/v1/payments/" + payment.getOrderNo() + "/approvals",
                Map.of("requestId", command, "txId", ATTEMPTS.get(payment.getPaymentId()), "paymentToken", "private-token"), member(payment));
    }
    private Reply closure(PaymentApproval approval, String command, String note, String role) {
        return http("POST", ROOT + "/" + approval.getApprovalId() + "/closure", Map.of("requestId", command, "note", note), token(role));
    }
    private Reply http(String method, String path, Map<String, String> body, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent, response) -> new Reply(response.getStatusCode().value(), response.getHeaders().getCacheControl(),
                response.getHeaders().getFirst("Location"), response.bodyTo(JsonNode.class)));
    }
    private record Reply(int status, String cache, String location, JsonNode body) { }

    private static void pg(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/payments/".length());
        String paymentId = path.split("/")[0]; Payment payment = PURCHASES.get(paymentId);
        if ("POST".equals(exchange.getRequestMethod())) {
            exchange.getRequestBody().readAllBytes(); POSTS.add(paymentId);
            if (REJECT.get()) { send(exchange, 400, Map.of("type", "INVALID_PAYMENT_TOKEN")); return; }
            if (FIRST_UNAVAILABLE.getAndSet(false)) { send(exchange, 503, Map.of()); return; }
            STATES.put(paymentId, "PAID"); send(exchange, 200, Map.of("pgTxId", "pg-" + paymentId)); return;
        }
        GETS.computeIfAbsent(paymentId, ignored -> new AtomicInteger()).incrementAndGet();
        if (PG_DOWN.get()) { send(exchange, 503, Map.of()); return; }
        if (path.endsWith("/transactions")) { send(exchange, 200, Map.of("items", List.of())); return; }
        if (payment == null) { send(exchange, 404, Map.of()); return; }
        String state = STATES.get(paymentId);
        boolean cancelled = "CANCELLED".equals(state) || "PARTIAL".equals(state);
        boolean money = "PAID".equals(state) || cancelled;
        long amount = payment.getAmount() + (WRONG_AMOUNT.get() ? 1 : 0);
        long returned = "CANCELLED".equals(state) ? amount : "PARTIAL".equals(state) ? amount / 2 : 0;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", cancelled ? "CANCELLED" : state); data.put("id", paymentId); data.put("transactionId", ATTEMPTS.get(paymentId));
        data.put("storeId", STORE); data.put("version", "V2"); data.put("currency", "KRW");
        data.put("channel", Map.of("type", "TEST", "key", CHANNEL, "pgProvider", "TOSSPAY_V2"));
        data.put("amount", Map.of("total", amount, "paid", money ? amount : 0, "discount", 0, "cancelled", returned));
        data.put("customData", JSON.writeValueAsString(Map.of("orderNo", payment.getOrderNo())));
        if (money) { data.put("paidAt", TIME.get().toString()); data.put("pgTxId", "pg-" + paymentId); }
        if ("FAILED".equals(state)) { data.put("failedAt", TIME.get().toString()); data.put("failure", Map.of("pgMessage", "fixture decline")); }
        if (cancelled) {
            data.put("cancelledAt", TIME.get().toString());
            data.put("cancellations", List.of(Map.of("id", "cancel-" + paymentId, "status", "SUCCEEDED", "totalAmount", returned, "cancelledAt", TIME.get().toString())));
        }
        send(exchange, 200, data);
    }
    private static void order(HttpExchange exchange) throws IOException {
        if (!"fixture-order-service-key".equals(exchange.getRequestHeaders().getFirst("X-Payment-Service-Key"))) { send(exchange, 403, Map.of()); return; }
        String[] parts = exchange.getRequestURI().getPath().substring("/internal/v1/orders/".length()).split("/");
        JsonNode body = JSON.readTree(exchange.getRequestBody()); String orderNo = parts[0];
        if (parts.length == 4) {
            String admission = parts[2];
            JsonNode receipt = FAILURE_NOTICES.computeIfAbsent(admission, ignored -> JSON.valueToTree(Map.of("admissionId", admission, "orderNo", orderNo,
                    "paymentId", body.path("paymentId").asText(), "closedAt", TIME.get().toString(), "orderStatus", "PENDING")));
            send(exchange, 200, Map.of("success", true, "data", receipt)); return;
        }
        String command = body.path("admissionId").asText();
        JsonNode known = RECEIPTS.computeIfAbsent(command, ignored -> {
            Payment payment = PURCHASES.get(body.path("paymentId").asText());
            return JSON.valueToTree(Map.of("admissionId", command, "orderNo", orderNo, "paymentId", payment.getPaymentId(),
                    "admittedAt", TIME.get().toString(), "expiresAt", payment.getOrderOccurredAt().plusSeconds(1800).toString()));
        });
        send(exchange, 200, Map.of("success", true, "data", known));
    }
    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
