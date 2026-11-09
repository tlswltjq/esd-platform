package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.PortOneFixtures.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.test.OpenApiSnapshot;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.OrderExpiryInput;
import com.stove.payment.core.service.ApplyOrderExpiry;
import com.stove.payment.core.domain.PaymentApproval;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.service.ResolvePaymentApproval;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "stove.payment.pg.provider=PORTONE_TEST","stove.payment.processing-enabled=false",
        "stove.outbox.relay-enabled=false","spring.kafka.listener.auto-startup=false"})
@Import({InfraContainers.MySql.class,InfraContainers.Kafka.class})
class PaymentApprovalIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final AtomicReference<Instant> TIME=new AtomicReference<>();
    private static final Map<String,Payment> PURCHASES=new ConcurrentHashMap<>();
    private static final Map<String,String> ATTEMPTS=new ConcurrentHashMap<>();
    private static final Map<String,String> STATES=new ConcurrentHashMap<>();
    private static final Map<String,JsonNode> RECEIPTS=new ConcurrentHashMap<>();
    private static final Map<String,JsonNode> CLOSURES=new ConcurrentHashMap<>();
    private static final List<String> BODIES=new CopyOnWriteArrayList<>();
    private static final List<String> KEYS=new CopyOnWriteArrayList<>();
    private static final AtomicInteger GETS=new AtomicInteger();
    private static final AtomicInteger PG_DISCOUNT=new AtomicInteger();
    private static final AtomicBoolean LOSE_PG=new AtomicBoolean();
    private static final AtomicBoolean LOSE_ORDER=new AtomicBoolean();
    private static final AtomicBoolean LOSE_CLOSURE=new AtomicBoolean();
    private static final AtomicBoolean PG_DOWN=new AtomicBoolean();
    private static final AtomicBoolean FIRST_UNAVAILABLE=new AtomicBoolean();
    private static final AtomicBoolean REJECT=new AtomicBoolean();
    private static final AtomicBoolean EXPIRED=new AtomicBoolean();
    private static final AtomicBoolean SALE_RESTRICTED=new AtomicBoolean();
    private static final AtomicBoolean WRONG_AMOUNT=new AtomicBoolean();
    private static final AtomicReference<CountDownLatch> ENTERED=new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> RELEASE=new AtomicReference<>();
    private static final HttpServer PG;
    private static final HttpServer ORDER;
    static {
        try {
            PG=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PG.createContext("/payments/",PaymentApprovalIntegrationTest::pg);
            PG.setExecutor(Executors.newCachedThreadPool()); PG.start();
            ORDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            ORDER.createContext("/internal/v1/orders/",PaymentApprovalIntegrationTest::order);
            ORDER.setExecutor(Executors.newCachedThreadPool()); ORDER.start();
            Runtime.getRuntime().addShutdownHook(new Thread(()->{ PG.stop(0); ORDER.stop(0); }));
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("stove.payment.portone.base-url",()->"http://127.0.0.1:"+PG.getAddress().getPort());
        registry.add("stove.payment.portone.api-secret",()->"fixture-api-secret");
        registry.add("stove.payment.portone.store-id",()->STORE); registry.add("stove.payment.portone.channel-key",()->CHANNEL);
        registry.add("stove.payment.portone.webhook-secret",()->SECRET);
        registry.add("stove.payment.portone.webhook-url",()->"https://payment-test.example/api/v1/payments/webhooks/portone");
        registry.add("stove.payment.approval.order-base-url",()->"http://127.0.0.1:"+ORDER.getAddress().getPort());
        registry.add("stove.payment.approval.order-service-key",()->"fixture-order-service-key");
        registry.add("stove.payment.approval.payload-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",()->PaymentIntegrationTestBase.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",()->"http://127.0.0.1:"+PaymentIntegrationTestBase.JWKS.getAddress().getPort()+"/jwks");
    }
    @Autowired ReceiveOrderPayment receive;
    @Autowired ApplyOrderExpiry expire;
    @Autowired ResolvePaymentApproval worker;
    @Autowired ResolvePayment resolve;
    @Autowired PaymentRepository payments;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoSpyBean OutboxRecorder outbox;
    @LocalServerPort int port;

    @BeforeEach void initialize() { clean(); when(clock.instant()).thenAnswer(call->TIME.get()); }
    @AfterEach void clean() {
        CountDownLatch release=RELEASE.getAndSet(null); if(release!=null)release.countDown(); ENTERED.set(null);
        reset(outbox); jdbc.update("DELETE FROM payment_pg_webhook"); jdbc.update("DELETE FROM payment_order_lifecycle"); jdbc.update("DELETE FROM purchase_payment");
        jdbc.update("DELETE FROM processed_event"); jdbc.update("DELETE FROM outbox_event");
        PURCHASES.clear(); ATTEMPTS.clear(); STATES.clear(); RECEIPTS.clear(); CLOSURES.clear(); BODIES.clear(); KEYS.clear(); GETS.set(0); PG_DISCOUNT.set(0);
        LOSE_PG.set(false); LOSE_ORDER.set(false); LOSE_CLOSURE.set(false); PG_DOWN.set(false); FIRST_UNAVAILABLE.set(false); REJECT.set(false); EXPIRED.set(false); SALE_RESTRICTED.set(false); WRONG_AMOUNT.set(false);
    }
    @Test void receptionIsDurableEncryptedAndSeparateFromFinancialSuccess() {
        Payment payment=purchase(); String command=id(); Reply first=submit(payment,command);
        assertThat(first.status()).isEqualTo(202); assertThat(first.cache()).isEqualTo("no-store");
        assertThat(first.location()).isEqualTo(path(payment)+"/"+command);
        assertThat(first.body().toString()).doesNotContain("private-token","tx-","protectedPayload","fingerprint");
        assertThat(row(command).getProtectedPayload()).startsWith("v1:").doesNotContain("private-token");
        assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
        assertThat(submit(payment,command).body()).isEqualTo(first.body());
        worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.SUCCEEDED);
        assertThat(row(command).getProtectedPayload()).isNull(); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(countOutbox()).isEqualTo(1); assertThat(BODIES).hasSize(1); assertThat(KEYS).containsExactly("\""+command+"\"");
        JsonNode sent=read(BODIES.get(0)); assertThat(sent.path("totalAmount").asLong()).isEqualTo(payment.getAmount());
        assertThat(sent.path("txId").asText()).isEqualTo(ATTEMPTS.get(payment.getPaymentId())); assertThat(sent.path("isTest").asBoolean()).isTrue();
        Reply progress=http("GET",first.location(),null,member(payment));
        assertThat(progress.body().path("data").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(progress.body().toString()).doesNotContain("private-token","tx-","fixture-api-secret");
        assertThat(submit(payment,command).body()).isEqualTo(first.body()); worker.execute(command); assertThat(countOutbox()).isEqualTo(1);
    }
    @Test void ownershipRolesAndChangedOrConcurrentCommandsCannotExposeOrDuplicateTheApproval() throws Exception {
        Payment payment=purchase(); String command=id();
        assertThat(http("POST",path(payment),body(payment,command),null).status()).isEqualTo(401);
        assertThat(http("POST",path(payment),body(payment,command),PaymentIntegrationTestBase.token("usr_buyer","SELLER")).status()).isEqualTo(403);
        assertThat(http("POST",path(payment),body(payment,command),PaymentIntegrationTestBase.token("other","MEMBER")).status()).isEqualTo(404);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            Future<Reply> first=pool.submit(()->submit(payment,command)); Future<Reply> second=pool.submit(()->submit(payment,command));
            assertThat(first.get(20,TimeUnit.SECONDS).body()).isEqualTo(second.get(20,TimeUnit.SECONDS).body());
        }
        assertThat(approvals.count()).isEqualTo(1); assertThat(submit(payment,id()).status()).isEqualTo(409);
        assertThat(http("POST",path(payment),Map.of("requestId",command,"txId",ATTEMPTS.get(payment.getPaymentId()),"paymentToken","changed"),member(payment)).status()).isEqualTo(409);
        assertThat(http("GET",path(payment)+"/"+command,null,PaymentIntegrationTestBase.token("other","MEMBER")).status()).isEqualTo(404);
        assertThat(http("GET","/api/v1/payments/"+payment.getOrderNo()+"/checkout",null,member(payment)).status()).isEqualTo(409);
        assertThat(BODIES).isEmpty();
    }
    @Test void anAppliedApprovalWithALostResponseIsRecoveredByLookupWithoutAnotherPost() {
        Payment payment=purchase(); String command=id(); submit(payment,command); LOSE_PG.set(true);
        worker.execute(command); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(STATES.get(payment.getPaymentId())).isEqualTo("PAID");
        advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.SUCCEEDED); assertThat(BODIES).hasSize(1); assertThat(countOutbox()).isEqualTo(1);
    }
    @Test void discountedPaidLookupRecoversTheLostResponseAndPersistsTheAmountsWithoutAnotherPost() throws Exception {
        Payment payment = purchase(); String command = id(); PG_DISCOUNT.set(24); LOSE_PG.set(true); submit(payment, command);
        worker.execute(command);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        advance(5); worker.execute(command);
        Payment paid = stored(payment);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.SUCCEEDED);
        assertThat(paid.getAmount()).isEqualTo(payment.getAmount());
        assertThat(paid.getDiscountAmount()).isEqualTo(payment.getDiscountAmount());
        assertThat(paid.getPgPaidAmount()).isEqualTo(payment.getAmount() - 24);
        assertThat(paid.getPgDiscountAmount()).isEqualTo(24);
        assertThat(paid.getPgFeeAmount()).isNull();
        JsonNode view = http("GET", "/api/v1/payments/" + payment.getOrderNo(), null, member(payment)).body().path("data");
        assertThat(view.path("pgPaidAmount").asLong()).isEqualTo(payment.getAmount() - 24);
        assertThat(view.path("pgDiscountAmount").asLong()).isEqualTo(24);
        JsonNode event = JSON.readTree(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_type='OrderPaymentCompleted'", String.class));
        assertThat(event.path("pgPaidAmount").asLong()).isEqualTo(payment.getAmount() - 24);
        assertThat(event.path("pgDiscountAmount").asLong()).isEqualTo(24);
        assertProviderAmountsConstraint(() -> jdbc.update("UPDATE purchase_payment SET pg_discount_amount=25 WHERE payment_id=?", payment.getPaymentId()));
        assertProviderAmountsConstraint(() -> jdbc.update("UPDATE purchase_payment SET pg_paid_amount=NULL WHERE payment_id=?", payment.getPaymentId()));
        worker.execute(command);
        assertThat(BODIES).hasSize(1); assertThat(countOutbox()).isEqualTo(1);
        assertThat(stored(payment).supplierResult()).isEqualTo(paid.supplierResult());
    }
    @Test void anUnappliedRequestIsRetransmittedWithTheIdenticalKeyAndBytes() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true);
        worker.execute(command); Instant original=row(command).getFirstPreparedAt(); assertThat(countOutbox()).isZero();
        advance(5); worker.execute(command);
        assertThat(BODIES).hasSize(2); assertThat(BODIES.get(0)).isEqualTo(BODIES.get(1)); assertThat(KEYS.get(0)).isEqualTo(KEYS.get(1));
        assertThat(row(command).getFirstPreparedAt()).isEqualTo(original); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
    }
    /** Decision 124: at the exact 3-hour boundary a READY approval stops posts and is held, and a late PAID still completes the order. */
    @Test void theExactThreeHourBoundaryStopsPostsButContinuesLookupAndAcceptsLateSuccess() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        Instant window=row(command).getFirstPreparedAt().plusSeconds(10800);
        TIME.set(window); int before=GETS.get(); worker.execute(command);
        assertThat(BODIES).hasSize(1); assertThat(GETS.get()).isGreaterThan(before);
        PaymentApproval held=row(command);
        assertThat(held.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(held.getLastErrorCode()).isEqualTo("CONFIRM_WINDOW_EXPIRED"); assertThat(held.getReviewStartedAt()).isEqualTo(window);
        assertThat(held.getLatestPgState()).isEqualTo(ApprovalObservation.State.READY); assertThat(held.getAdmittedAt()).isNotNull();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(countOutbox()).isZero();
        STATES.put(payment.getPaymentId(),"PAID"); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.SUCCEEDED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(countOutbox()).isEqualTo(1); assertThat(BODIES).hasSize(1);
    }
    /**
     * D-051: a held admitted approval whose payment the payment resolver (pending scheduler or webhook recheck) confirms
     * PAID must close in that same commit. Expected: the approval is SUCCEEDED, so neither customer read shows a held
     * notice and the ADMIN hold list and gauge (both select REVIEW_REQUIRED) no longer count it. Actual before the fix: the
     * approval stays REVIEW_REQUIRED with its old READY observation until the next held lookup (up to 10 minutes, decision
     * 122), and the customer of a PAID, completed order reads NOT_CHARGED ("새 주문으로 다시 구매하실 수 있습니다").
     * Impact: a duplicate purchase invitation (decision 123) after the money was taken.
     */
    @Test
    @Tag("known-defect")
    @DisplayName("[D-051] 접수된 보류 승인의 결제가 PAID로 확정되면 같은 커밋에서 승인도 닫혀 보류 안내·게이지·목록에 남지 않는다")
    void aHeldAdmittedApprovalClosesInThePaidPaymentCommitSoNoStaleHeldNoticeRemains() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        TIME.set(row(command).getFirstPreparedAt().plusSeconds(10800)); worker.execute(command);
        PaymentApproval held=row(command);
        assertThat(held.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(held.getLastErrorCode()).isEqualTo("CONFIRM_WINDOW_EXPIRED");
        assertThat(held.getLatestPgState()).isEqualTo(ApprovalObservation.State.READY);
        assertThat(paymentDetail(payment).path("reviewNotice").path("code").asText()).isEqualTo("NOT_CHARGED");
        STATES.put(payment.getPaymentId(),"PAID"); advance(5);
        resolve.execute(payment.getPaymentId());
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(countOutbox()).isEqualTo(1);
        PaymentApproval closed=row(command);
        assertThat(closed.getStatus()).as("approval status right after the PAID commit").isEqualTo(PaymentApprovalStatus.SUCCEEDED);
        assertThat(closed.getNextAt()).isNull(); assertThat(closed.getProtectedPayload()).isNull();
        assertThat(paymentDetail(payment).path("reviewNotice").isNull()).isTrue();
        assertThat(approvalDetail(payment,command).path("reviewNotice").isNull()).isTrue();
        assertThat(approvals.findReviews(PageRequest.of(0,1000)).map(PaymentApproval::getApprovalId).getContent()).doesNotContain(command);
        int gets=GETS.get(); worker.execute(command);
        assertThat(GETS.get()).isEqualTo(gets); assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.SUCCEEDED);
        assertThat(BODIES).hasSize(1); assertThat(countOutbox()).isEqualTo(1);
    }
    /**
     * Decision 124 (and the decision 120 correction): an admitted approval whose PG stays PAY_PENDING is only looked up before
     * the 3-hour window ends, is held with CONFIRM_WINDOW_EXPIRED right after the first lookup at the window, and a late
     * FAILED still closes the original order without a new key, a new payment or a financial failure invented by time.
     */
    @Test void anAdmittedPayPendingApprovalPastTheConfirmWindowIsHeldAndALateFailureClosesTheOriginalOrder() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        STATES.put(payment.getPaymentId(),"PAY_PENDING");
        Instant window=row(command).getFirstPreparedAt().plusSeconds(10800);
        // The last lookup before the window (its 5 s retry lands exactly on the window) stays an unheld admitted approval.
        TIME.set(window.minusSeconds(5)); int before=GETS.get(); worker.execute(command);
        assertThat(GETS.get()).isEqualTo(before+1); assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.ADMITTED);
        assertThat(row(command).getNextAt()).isEqualTo(window);
        assertThat(row(command).getReviewStartedAt()).isNull(); assertThat(row(command).getLatestPgState()).isEqualTo(ApprovalObservation.State.PENDING);
        TIME.set(window); before=GETS.get(); worker.execute(command);
        PaymentApproval held=row(command);
        assertThat(GETS.get()).isEqualTo(before+1);
        assertThat(held.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(held.getLastErrorCode()).isEqualTo("CONFIRM_WINDOW_EXPIRED"); assertThat(held.getReviewStartedAt()).isEqualTo(window);
        assertThat(held.isPostStopped()).isTrue(); assertThat(held.getNextAt()).isEqualTo(window.plusSeconds(5));
        assertThat(held.getProtectedPayload()).isNotNull(); assertThat(held.getFailedAt()).isNull();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(BODIES).hasSize(1); assertThat(countOutbox()).isZero(); assertThat(CLOSURES).isEmpty();
        assertThat(submit(payment,id()).status()).isEqualTo(409);
        STATES.put(payment.getPaymentId(),"FAILED"); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.FAILED);
        assertThat(row(command).getLastErrorCode()).isEqualTo("CONFIRM_WINDOW_EXPIRED");
        assertThat(CLOSURES.get(command).path("orderStatus").asText()).isEqualTo("EXPIRED");
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(BODIES).hasSize(1); assertThat(countOutbox()).isZero();
    }
    @Test void aLostOrderReceiptIsReplayedAfterTheDeadlineAndPreservesTheOriginalAdmission() {
        Payment payment=purchase(); String command=id(); submit(payment,command); LOSE_ORDER.set(true); worker.execute(command);
        JsonNode original=RECEIPTS.get(command); assertThat(original).isNotNull(); assertThat(row(command).getAdmittedAt()).isNull(); assertThat(BODIES).isEmpty();
        TIME.set(payment.getOrderOccurredAt().plusSeconds(1801)); worker.execute(command);
        assertThat(row(command).getAdmittedAt()).isEqualTo(Instant.parse(original.path("admittedAt").asText()));
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(BODIES).hasSize(1);
    }
    @Test void explicitOrderExpiryRejectsTheApprovalWithoutSendingItToTheProvider() {
        Payment payment=purchase(); String command=id(); submit(payment,command); EXPIRED.set(true); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED); assertThat(row(command).getProtectedPayload()).isNull();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED); assertThat(BODIES).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void aPaidSupplierSnapshotWithoutOrderAdmissionCannotPublishSuccess() {
        Payment payment=purchase(); STATES.put(payment.getPaymentId(),"PAID"); resolve.execute(payment.getPaymentId());
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(countOutbox()).isZero();
        String command=id(); submit(payment,command); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED); assertThat(RECEIPTS).isEmpty(); assertThat(BODIES).isEmpty();
        assertThat(countOutbox()).isZero();
    }
    @Test void mismatchingPurchaseEvidenceStopsBeforeOrderAdmissionAndProviderPost() {
        Payment payment=purchase(); String command=id(); submit(payment,command); WRONG_AMOUNT.set(true); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED); assertThat(RECEIPTS).isEmpty(); assertThat(BODIES).isEmpty();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(countOutbox()).isZero();
    }
    @Test void hardProviderRejectionStopsPostsAndKeepsTheUncertainPurchaseBlocked() {
        Payment payment=purchase(); String command=id(); submit(payment,command); REJECT.set(true); worker.execute(command); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED); assertThat(BODIES).hasSize(1);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(countOutbox()).isZero();
        assertThat(submit(payment,id()).status()).isEqualTo(409);
    }
    /** Decision 122: a held approval is looked up again after min(10 min, max(5 s, time held)); a legacy hold uses the cap. */
    @Test void aHeldApprovalIsLookedUpLessOftenTheLongerItHasBeenHeld() {
        Payment payment=purchase(); String command=id(); submit(payment,command); REJECT.set(true); worker.execute(command);
        Instant held=row(command).getReviewStartedAt();
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(row(command).getNextAt()).isEqualTo(held.plusSeconds(5));
        TIME.set(held.plusSeconds(60)); int before=GETS.get(); worker.execute(command);
        assertThat(GETS.get()).isGreaterThan(before); assertThat(row(command).getNextAt()).isEqualTo(held.plusSeconds(120));
        TIME.set(held.plusSeconds(119)); before=GETS.get(); worker.execute(command);
        assertThat(GETS.get()).isEqualTo(before); assertThat(row(command).getNextAt()).isEqualTo(held.plusSeconds(120));
        TIME.set(held.plusSeconds(3600)); worker.execute(command);
        assertThat(row(command).getNextAt()).isEqualTo(held.plusSeconds(3600+600));
        jdbc.update("UPDATE payment_approval SET review_started_at=NULL WHERE approval_id=?",command);
        TIME.set(held.plusSeconds(4200)); worker.execute(command);
        assertThat(row(command).getNextAt()).isEqualTo(held.plusSeconds(4200+600));
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED); assertThat(BODIES).hasSize(1);
    }
    /** Decision 122 leaves PAYMENT_RETRY_MS alone: an approval that is not held keeps the 5 s lookup however long it waits. */
    @Test void anUnheldApprovalKeepsTheGeneralRetryDelayHoweverLongItWaits() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.ADMITTED);
        assertThat(row(command).getNextAt()).isEqualTo(TIME.get().plusSeconds(5));
        PG_DOWN.set(true); TIME.set(TIME.get().plusSeconds(3600)); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.ADMITTED); assertThat(row(command).getReviewStartedAt()).isNull();
        assertThat(row(command).getNextAt()).isEqualTo(TIME.get().plusSeconds(5));
    }

    @Test void aPlatformRestrictionWaitsWithoutPostingAndResumesTheSameUnexpiredApprovalAfterRelease() {
        Payment payment=purchase(); String command=id(); submit(payment,command); SALE_RESTRICTED.set(true);
        worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REQUESTED);
        assertThat(row(command).getLastErrorCode()).isEqualTo("ORDER_SALE_RESTRICTED");
        assertThat(row(command).getFirstPreparedAt()).isNull();
        assertThat(row(command).getAdmittedAt()).isNull();
        assertThat(row(command).getNextAt()).isAfter(TIME.get());
        assertThat(RECEIPTS).isEmpty(); assertThat(BODIES).isEmpty(); assertThat(countOutbox()).isZero();
        SALE_RESTRICTED.set(false); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.SUCCEEDED);
        assertThat(row(command).getLastErrorCode()).isNull();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(BODIES).hasSize(1); assertThat(KEYS).containsExactly("\""+command+"\"");
        assertThat(row(command).getExpiresAt()).isEqualTo(payment.getOrderOccurredAt().plusSeconds(1800));
    }

    @Test void aRestrictionCannotExtendTheOrderDeadlineOrTurnAnUnadmittedExpiryIntoAFinancialFailure() {
        Payment payment=purchase(); String command=id(); submit(payment,command); SALE_RESTRICTED.set(true); worker.execute(command);
        TIME.set(payment.getOrderOccurredAt().plusSeconds(1800)); EXPIRED.set(true); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void aVerifiedIndividualFailureReleasesOrderAdmissionAndAllowsANewAttemptOnTheSamePurchase() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        STATES.put(payment.getPaymentId(),"FAILED"); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.FAILED); assertThat(CLOSURES.get(command).path("orderStatus").asText()).isEqualTo("PENDING");
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(countOutbox()).isZero();
        ATTEMPTS.put(payment.getPaymentId(),"tx-"+id()); STATES.put(payment.getPaymentId(),"READY"); String retry=id(); assertThat(submit(payment,retry).status()).isEqualTo(202);
        worker.execute(retry); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(countOutbox()).isEqualTo(1);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.FAILED); assertThat(approvals.count()).isEqualTo(2);
    }
    @Test void aVerifiedFailureAfterTheOrderDeadlineClosesThePurchaseWithoutAFinancialFailureEvent() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        TIME.set(payment.getOrderOccurredAt().plusSeconds(1800)); STATES.put(payment.getPaymentId(),"FAILED"); worker.execute(command);
        assertThat(CLOSURES.get(command).path("orderStatus").asText()).isEqualTo("EXPIRED");
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED); assertThat(countOutbox()).isZero();
    }
    @Test void concurrentWorkersHaveOnePostAndReleasePaymentDatabaseLocksBeforeHttp() throws Exception {
        Payment payment=purchase(); String command=id(); submit(payment,command); ENTERED.set(new CountDownLatch(1)); RELEASE.set(new CountDownLatch(1));
        try(ExecutorService pool=Executors.newFixedThreadPool(3)) {
            Future<?> first=pool.submit(()->worker.execute(command)); assertThat(ENTERED.get().await(15,TimeUnit.SECONDS)).isTrue();
            Future<?> second=pool.submit(()->worker.execute(command)); second.get(5,TimeUnit.SECONDS);
            Future<?> database=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->payments.lockByPaymentId(payment.getPaymentId()).orElseThrow()));
            database.get(5,TimeUnit.SECONDS); assertThat(BODIES).hasSize(1); RELEASE.get().countDown(); first.get(20,TimeUnit.SECONDS);
        } finally { RELEASE.get().countDown(); }
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(countOutbox()).isEqualTo(1);
    }
    @Test void aRealOutboxFlushFailureRollsBackSuccessAndLookupRecoversWithoutAnotherPost() {
        Payment payment=purchase(); String command=id(); submit(payment,command);
        new TransactionTemplate(transactions).executeWithoutResult(tx->doAnswer(call->{
            call.callRealMethod(); entityManager.flush(); throw new IllegalStateException("after real completion flush");
        }).when(outbox).record(anyString(),anyString(),any(DomainEvent.class)));
        worker.execute(command); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(countOutbox()).isZero();
        reset(outbox); advance(5); worker.execute(command);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(countOutbox()).isEqualTo(1); assertThat(BODIES).hasSize(1);
    }
    @Test void aLostFailureAcknowledgementUsesTheStoredProofAndOriginalClosureWithoutAnotherPgLookup() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        STATES.put(payment.getPaymentId(),"FAILED"); LOSE_CLOSURE.set(true); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.FAILURE_CONFIRMED);
        JsonNode first=CLOSURES.get(command); assertThat(first.path("orderStatus").asText()).isEqualTo("PENDING");
        int before=GETS.get(); PG_DOWN.set(true); TIME.set(payment.getOrderOccurredAt().plusSeconds(1801)); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.FAILED); assertThat(GETS.get()).isEqualTo(before);
        assertThat(CLOSURES.get(command)).isEqualTo(first); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
    @Test void aPersistedLeaseSurvivesWorkerLossAndResumesWithTheOriginalPreparationAndKey() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        Instant prepared=row(command).getFirstPreparedAt(); advance(5);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            PaymentApproval approval=approvals.lockByApprovalId(command).orElseThrow();
            assertThat(approval.claim(id(),TIME.get(),Duration.ofSeconds(60))).isTrue();
        });
        int before=GETS.get(); advance(59); worker.execute(command); assertThat(GETS.get()).isEqualTo(before);
        advance(1); worker.execute(command);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(row(command).getFirstPreparedAt()).isEqualTo(prepared);
        assertThat(BODIES).hasSize(2); assertThat(BODIES.get(0)).isEqualTo(BODIES.get(1)); assertThat(KEYS.get(0)).isEqualTo(KEYS.get(1));
    }
    @Test void portOneOpenApiIncludesTheApprovalReceptionAndProgressContracts() {
        OpenApiSnapshot.verify(port,"payment-portone");
        JsonNode paths=http("GET","/v3/api-docs",null,null).body().path("paths");
        assertThat(paths.path("/api/v1/payments/{orderNo}/approvals").has("post")).isTrue();
        assertThat(paths.path("/api/v1/payments/{orderNo}/approvals/{requestId}").has("get")).isTrue();
        assertThat(paths.path("/api/v1/payments/{orderNo}/checkout").has("get")).isTrue();
        assertThat(paths.path("/api/v1/admin/approval-reviews/{approvalId}/closure").has("post")).isTrue();
    }
    @Test void anOrderExpiryReceiptClosesAQueuedUnadmittedApprovalWithoutPgTraffic() {
        Payment payment=purchase(); String command=id(); submit(payment,command); recordExpiry(payment);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REQUESTED); assertThat(row(command).getLatestPgState()).isNull();
        int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
        assertThat(row(command).getProtectedPayload()).isNull(); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(GETS.get()).isEqualTo(before); assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty();
        Reply progress=http("GET",path(payment)+"/"+command,null,member(payment));
        assertThat(progress.body().path("data").path("status").asText()).isEqualTo("EXPIRED");
        assertThat(submit(payment,id()).status()).isEqualTo(409);
        assertThat(http("GET","/api/v1/payments/"+payment.getOrderNo(),null,member(payment)).body().path("data").path("status").asText()).isEqualTo("EXPIRED");
        assertThat(countOutbox()).isZero();
    }
    @Test void expiryWhileTheFailureAcknowledgementIsLostWaitsForTheOriginalClosureThenClosesTheParent() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        STATES.put(payment.getPaymentId(),"FAILED"); LOSE_CLOSURE.set(true); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.FAILURE_CONFIRMED);
        assertThat(CLOSURES.get(command).path("orderStatus").asText()).isEqualTo("PENDING");
        TIME.set(payment.getOrderOccurredAt().plusSeconds(1801)); recordExpiry(payment);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        worker.execute(command); assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.FAILED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED); assertThat(countOutbox()).isZero(); assertThat(BODIES).hasSize(1);
    }
    @Test void correctedUnadmittedEvidenceExpiresOnlyAfterTheOrderExpiryReceipt() {
        Payment payment=purchase(); String command=id(); submit(payment,command); WRONG_AMOUNT.set(true); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        WRONG_AMOUNT.set(false); TIME.set(payment.getOrderOccurredAt().plusSeconds(1801)); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        // Held for about 30 minutes, the next lookup comes 10 minutes later (decision 122), not after the 5 s retry delay.
        recordExpiry(payment); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(GETS.get()).isEqualTo(before); assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        advance(595); worker.execute(command);
        PaymentApproval closed=row(command); Instant resolvedAt=closed.getResolvedAt();
        assertThat(closed.getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
        assertThat(closed.getAdmittedAt()).isNull(); assertThat(closed.getFirstPreparedAt()).isNull();
        assertThat(closed.getProtectedPayload()).isNull(); assertThat(closed.getNextAt()).isNull();
        assertThat(closed.getLastErrorCode()).isEqualTo("PG_INVALID_EVIDENCE");
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(stored(payment).getNextAttemptAt()).isNull(); assertThat(stored(payment).getNextFailureLookupAt()).isNull();
        assertThat(GETS.get()).isGreaterThan(before); assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty();
        assertThat(countOutbox()).isZero();
        Reply progress=http("GET",path(payment)+"/"+command,null,member(payment));
        assertThat(progress.body().path("data").path("status").asText()).isEqualTo("EXPIRED");
        advance(5); before=GETS.get(); worker.execute(command);
        assertThat(row(command).getResolvedAt()).isEqualTo(resolvedAt); assertThat(GETS.get()).isEqualTo(before);
    }
    @Test void orderExpiryCannotCloseAReviewWhilePurchaseEvidenceIsStillInvalid() {
        Payment payment=purchase(); String command=id(); submit(payment,command); WRONG_AMOUNT.set(true); worker.execute(command);
        recordExpiry(payment); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row(command).getProtectedPayload()).isNotNull(); assertThat(row(command).getNextAt()).isNotNull();
        assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void aDatabaseFailureRollsBackBothTheReviewedApprovalAndItsPaymentExpiry() {
        Payment payment=purchase(); String command=id(); submit(payment,command); WRONG_AMOUNT.set(true); worker.execute(command);
        WRONG_AMOUNT.set(false); recordExpiry(payment); advance(5);
        jdbc.execute("ALTER TABLE purchase_payment ADD CONSTRAINT chk_fixture_review_expiry CHECK (status <> 'EXPIRED')");
        try {
            worker.execute(command);
            assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
            assertThat(row(command).getProtectedPayload()).isNotNull(); assertThat(row(command).getResolvedAt()).isNull();
            assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        } finally {
            jdbc.execute("ALTER TABLE purchase_payment DROP CHECK chk_fixture_review_expiry");
        }
        advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void orderExpiryCannotCloseAReviewWhileTheProviderLookupIsUnavailable() {
        Payment payment=purchase(); String command=id(); submit(payment,command); WRONG_AMOUNT.set(true); worker.execute(command);
        WRONG_AMOUNT.set(false); PG_DOWN.set(true); recordExpiry(payment); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row(command).getProtectedPayload()).isNotNull(); assertThat(row(command).getNextAt()).isNotNull();
        assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void orderExpiryCannotCloseAReviewWithAProviderPaymentStillPending() {
        Payment payment=purchase(); String command=id(); submit(payment,command); WRONG_AMOUNT.set(true); worker.execute(command);
        WRONG_AMOUNT.set(false); STATES.put(payment.getPaymentId(),"PAY_PENDING"); recordExpiry(payment); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row(command).getProtectedPayload()).isNotNull(); assertThat(row(command).getNextAt()).isNotNull();
        assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void aLaterInvalidLookupCannotMakeAnUnadmittedPaidObservationSafeToExpire() {
        Payment payment=purchase(); String command=id(); submit(payment,command); STATES.put(payment.getPaymentId(),"PAID"); worker.execute(command);
        recordExpiry(payment); WRONG_AMOUNT.set(true); advance(5); worker.execute(command);
        WRONG_AMOUNT.set(false); STATES.put(payment.getPaymentId(),"READY"); advance(5); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(row(command).getLastErrorCode()).isEqualTo("PAID_WITHOUT_ADMISSION");
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(row(command).getProtectedPayload()).isNotNull(); assertThat(BODIES).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void anExpiryNoticeCannotEraseAnAlreadyObservedUnadmittedFinancialAnomaly() {
        Payment payment=purchase(); String command=id(); submit(payment,command); STATES.put(payment.getPaymentId(),"PAID"); worker.execute(command);
        recordExpiry(payment); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(GETS.get()).isGreaterThan(before);
        assertThat(row(command).getProtectedPayload()).isNotNull(); assertThat(BODIES).isEmpty(); assertThat(countOutbox()).isZero();
    }
    /**
     * [D-044] Regression (decision 126): a purchase the PG approved without our order admission stays held as
     * PAID_WITHOUT_ADMISSION (decision 114), even when the order expired while the PG still showed PAY_PENDING. Before the
     * fix the expiry branch closed the approval and the payment as EXPIRED without looking up the PG, so the later PAID was
     * never seen and money stayed taken for an expired order with no hold, gauge or alert.
     */
    @Test
    @DisplayName("[D-044] 접수 전 PAY_PENDING 뒤 주문이 만료되고 PG가 PAID가 되면 PAID_WITHOUT_ADMISSION 보류로 남는다")
    void aPaidProviderPaymentAfterAnUnadmittedPendingExpiryIsHeldAsPaidWithoutAdmission() {
        Payment payment=purchase(); String command=id(); submit(payment,command); STATES.put(payment.getPaymentId(),"PAY_PENDING");
        worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REQUESTED);
        assertThat(row(command).getLatestPgState()).isEqualTo(ApprovalObservation.State.PENDING); assertThat(row(command).getAdmittedAt()).isNull();
        recordExpiry(payment); STATES.put(payment.getPaymentId(),"PAID"); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(row(command).getLastErrorCode()).isEqualTo("PAID_WITHOUT_ADMISSION");
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(GETS.get()).isGreaterThan(before); assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    /** [D-044] Decision 126: an unadmitted approval that saw PAY_PENDING and still sees it after the order expiry is held, not closed. */
    @Test
    @DisplayName("[D-044] 접수 전 PAY_PENDING 관측 건이 만료 뒤에도 PENDING이면 PG_PENDING_AT_ORDER_EXPIRY 보류로 올린다")
    void anUnadmittedPendingObservationStillPendingAtOrderExpiryIsHeld() {
        Payment payment=purchase(); String command=id(); submit(payment,command); STATES.put(payment.getPaymentId(),"PAY_PENDING");
        worker.execute(command);
        assertThat(row(command).getLatestPgState()).isEqualTo(ApprovalObservation.State.PENDING);
        recordExpiry(payment); advance(5); int before=GETS.get(); worker.execute(command);
        PaymentApproval held=row(command);
        assertThat(held.getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(held.getLastErrorCode()).isEqualTo("PG_PENDING_AT_ORDER_EXPIRY");
        assertThat(held.getReviewStartedAt()).isNotNull(); assertThat(held.getAdmittedAt()).isNull();
        assertThat(held.getProtectedPayload()).isNotNull(); assertThat(held.getNextAt()).isNotNull();
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(GETS.get()).isEqualTo(before+1); assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
        // The hold keeps looking up the PG (decision 122): a later PAID becomes the money-taken hold, not a silent close.
        STATES.put(payment.getPaymentId(),"PAID"); advance(600); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(row(command).getLastErrorCode()).isEqualTo("PAID_WITHOUT_ADMISSION");
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(BODIES).isEmpty();
    }
    /** [D-044] Decision 126: a still-unadmitted approval whose PG turned READY again closes as before, after one lookup. */
    @Test void anUnadmittedPendingObservationThatIsReadyAtOrderExpiryClosesAfterOneLookup() {
        Payment payment=purchase(); String command=id(); submit(payment,command); STATES.put(payment.getPaymentId(),"PAY_PENDING");
        worker.execute(command);
        recordExpiry(payment); STATES.put(payment.getPaymentId(),"READY"); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
        assertThat(row(command).getProtectedPayload()).isNull(); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(GETS.get()).isEqualTo(before+1); assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    /** [D-044] Decision 126 keeps R11.5 for approvals with no valid PG observation: a failed lookup alone is not an observation. */
    @Test void anUnadmittedApprovalWithOnlyAFailedLookupStillClosesOnOrderExpiryWithoutPgTraffic() {
        Payment payment=purchase(); String command=id(); submit(payment,command); PG_DOWN.set(true); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REQUESTED); assertThat(row(command).getLatestPgState()).isNull();
        PG_DOWN.set(false); recordExpiry(payment); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.EXPIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(GETS.get()).isEqualTo(before); assertThat(BODIES).isEmpty(); assertThat(RECEIPTS).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void aConflictingExpiryAndAdmissionStopFurtherPostsWhileLookupContinues() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        recordExpiry(payment); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(row(command).getAdmittedAt()).isNotNull(); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(GETS.get()).isGreaterThan(before); assertThat(BODIES).hasSize(1); assertThat(countOutbox()).isZero();
    }
    private void assertProviderAmountsConstraint(Runnable update) {
        assertThatThrownBy(update::run).isInstanceOf(DataAccessException.class).satisfies(failure -> {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getErrorCode()).isEqualTo(3819);
            assertThat(cause.getMessage()).contains("ck_payment_provider_amounts");
        });
    }
    private void recordExpiry(Payment payment) {
        expire.execute(new OrderExpiryInput(id(),payment.getOrderOccurredAt().plusSeconds(1801),payment.getOrderNo(),payment.getRequestEventId(),payment.getBuyerSubject(),payment.getAmount(),payment.getCurrency(),payment.getOrderOccurredAt().plusSeconds(1800)));
    }
    private Payment purchase() {
        Payment payment=receive.execute(request()); TIME.set(payment.getCreatedAt().plusSeconds(1));
        PURCHASES.put(payment.getPaymentId(),payment); ATTEMPTS.put(payment.getPaymentId(),"tx-"+id()); STATES.put(payment.getPaymentId(),"READY"); return payment;
    }
    private Payment stored(Payment payment) { return payments.findById(payment.getId()).orElseThrow(); }
    private PaymentApproval row(String command) { return approvals.findByApprovalId(command).orElseThrow(); }
    private int countOutbox() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event",Integer.class); }
    private void advance(int seconds) { TIME.set(TIME.get().plusSeconds(seconds)); }
    private String path(Payment payment) { return "/api/v1/payments/"+payment.getOrderNo()+"/approvals"; }
    private String member(Payment payment) { return PaymentIntegrationTestBase.token(payment.getBuyerSubject(),"MEMBER"); }
    private Map<String,String> body(Payment payment,String command) { return Map.of("requestId",command,"txId",ATTEMPTS.get(payment.getPaymentId()),"paymentToken","private-token"); }
    private Reply submit(Payment payment,String command) { return http("POST",path(payment),body(payment,command),member(payment)); }
    private JsonNode paymentDetail(Payment payment) {
        Reply read=http("GET","/api/v1/payments/"+payment.getOrderNo(),null,member(payment));
        assertThat(read.status()).isEqualTo(200); return read.body().path("data");
    }
    private JsonNode approvalDetail(Payment payment,String command) {
        Reply read=http("GET",path(payment)+"/"+command,null,member(payment));
        assertThat(read.status()).isEqualTo(200); return read.body().path("data");
    }
    private Reply http(String method,String path,Map<String,String> body,String token) {
        RestClient.RequestBodySpec request=RestClient.create("http://127.0.0.1:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(token!=null)request.header("Authorization","Bearer "+token); if(body!=null)request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.exchange((sent,response)->new Reply(response.getStatusCode().value(),response.getHeaders().getCacheControl(),
                response.getHeaders().getFirst("Location"),response.bodyTo(JsonNode.class)));
    }
    private record Reply(int status,String cache,String location,JsonNode body) { }
    private static void pg(HttpExchange exchange) throws IOException {
        String path=exchange.getRequestURI().getPath().substring("/payments/".length()); boolean post="POST".equals(exchange.getRequestMethod());
        String paymentId=path.split("/")[0]; Payment payment=PURCHASES.get(paymentId);
        if(!post) {
            GETS.incrementAndGet(); if(PG_DOWN.get()) { send(exchange,503,Map.of()); return; } if(path.endsWith("/transactions")) { send(exchange,200,Map.of("items",List.of())); return; }
            if(payment==null) { send(exchange,404,Map.of()); return; }
            String state=STATES.get(paymentId); long amount=payment.getAmount()+(WRONG_AMOUNT.get()?1:0);
            Map<String,Object> data=new LinkedHashMap<>();
            data.putAll(Map.of("status",state,"id",paymentId,"transactionId",ATTEMPTS.get(paymentId),"storeId",STORE,"version","V2","currency","KRW",
                    "channel",Map.of("type","TEST","key",CHANNEL,"pgProvider","TOSSPAY_V2"),
                    "amount",Map.of("total",amount,"paid","PAID".equals(state)?amount-PG_DISCOUNT.get():0,"discount",PG_DISCOUNT.get(),"cancelled",0),
                    "customData",JSON.writeValueAsString(Map.of("orderNo",payment.getOrderNo()))));
            if("PAID".equals(state)) { data.put("paidAt",TIME.get().toString()); data.put("pgTxId","pg-"+paymentId); }
            if("FAILED".equals(state)) { data.put("failedAt",TIME.get().toString()); data.put("failure",Map.of("pgMessage","fixture decline")); }
            send(exchange,200,data); return;
        }
        BODIES.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        CountDownLatch entered=ENTERED.get(); if(entered!=null) { entered.countDown(); try { RELEASE.get().await(15,TimeUnit.SECONDS); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
        if(REJECT.get()) { send(exchange,400,Map.of("type","INVALID_PAYMENT_TOKEN","message","private-token")); return; }
        if(FIRST_UNAVAILABLE.getAndSet(false)) { send(exchange,503,Map.of()); return; }
        STATES.put(paymentId,"PAID"); if(LOSE_PG.getAndSet(false)) { exchange.close(); return; }
        send(exchange,200,Map.of("pgTxId","pg-"+paymentId,"paidAt",TIME.get().toString()));
    }
    private static void order(HttpExchange exchange) throws IOException {
        if(!"fixture-order-service-key".equals(exchange.getRequestHeaders().getFirst("X-Payment-Service-Key"))) { send(exchange,403,Map.of()); return; }
        String[] parts=exchange.getRequestURI().getPath().substring("/internal/v1/orders/".length()).split("/");
        JsonNode body=JSON.readTree(exchange.getRequestBody()); String orderNo=parts[0];
        if(parts.length==4) {
            String admission=parts[2]; JsonNode receipt=CLOSURES.computeIfAbsent(admission,ignored->{
                Instant expires=Instant.parse(RECEIPTS.get(admission).path("expiresAt").asText());
                return JSON.valueToTree(Map.of("admissionId",admission,"orderNo",orderNo,"paymentId",body.path("paymentId").asText(),"closedAt",TIME.get().toString(),"orderStatus",TIME.get().isBefore(expires)?"PENDING":"EXPIRED")); });
            if(LOSE_CLOSURE.getAndSet(false)) { exchange.close(); return; }
            send(exchange,200,Map.of("success",true,"data",receipt)); return;
        }
        String command=body.path("admissionId").asText(); JsonNode known=RECEIPTS.get(command);
        if(known==null) {
            if(EXPIRED.get()) { send(exchange,409,Map.of("success",false,"error",Map.of("code","ORDER_EXPIRED"))); return; }
            if(SALE_RESTRICTED.get()) { send(exchange,409,Map.of("success",false,"error",Map.of("code","PRODUCT_SALE_RESTRICTED"))); return; }
            Payment payment=PURCHASES.get(body.path("paymentId").asText());
            known=JSON.valueToTree(Map.of("admissionId",command,"orderNo",orderNo,"paymentId",payment.getPaymentId(),"admittedAt",TIME.get().toString(),"expiresAt",payment.getOrderOccurredAt().plusSeconds(1800).toString()));
            RECEIPTS.put(command,known);
        }
        if(LOSE_ORDER.getAndSet(false)) { exchange.close(); return; }
        send(exchange,200,Map.of("success",true,"data",known));
    }
    private static JsonNode read(String body) { try { return JSON.readTree(body); } catch(IOException invalid) { throw new IllegalArgumentException(invalid); } }
    private static void send(HttpExchange exchange,int status,Object body) throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
