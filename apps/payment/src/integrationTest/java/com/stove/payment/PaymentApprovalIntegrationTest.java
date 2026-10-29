package com.stove.payment;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PaymentFixtures.request;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.PortOneFixtures.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
    private static final AtomicBoolean LOSE_PG=new AtomicBoolean();
    private static final AtomicBoolean LOSE_ORDER=new AtomicBoolean();
    private static final AtomicBoolean LOSE_CLOSURE=new AtomicBoolean();
    private static final AtomicBoolean PG_DOWN=new AtomicBoolean();
    private static final AtomicBoolean FIRST_UNAVAILABLE=new AtomicBoolean();
    private static final AtomicBoolean REJECT=new AtomicBoolean();
    private static final AtomicBoolean EXPIRED=new AtomicBoolean();
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
        PURCHASES.clear(); ATTEMPTS.clear(); STATES.clear(); RECEIPTS.clear(); CLOSURES.clear(); BODIES.clear(); KEYS.clear(); GETS.set(0);
        LOSE_PG.set(false); LOSE_ORDER.set(false); LOSE_CLOSURE.set(false); PG_DOWN.set(false); FIRST_UNAVAILABLE.set(false); REJECT.set(false); EXPIRED.set(false); WRONG_AMOUNT.set(false);
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
    @Test void anUnappliedRequestIsRetransmittedWithTheIdenticalKeyAndBytes() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true);
        worker.execute(command); Instant original=row(command).getFirstPreparedAt(); assertThat(countOutbox()).isZero();
        advance(5); worker.execute(command);
        assertThat(BODIES).hasSize(2); assertThat(BODIES.get(0)).isEqualTo(BODIES.get(1)); assertThat(KEYS.get(0)).isEqualTo(KEYS.get(1));
        assertThat(row(command).getFirstPreparedAt()).isEqualTo(original); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID);
    }
    @Test void theExactThreeHourBoundaryStopsPostsButContinuesLookupAndAcceptsLateSuccess() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        TIME.set(row(command).getFirstPreparedAt().plusSeconds(10800)); int before=GETS.get(); worker.execute(command);
        assertThat(BODIES).hasSize(1); assertThat(GETS.get()).isGreaterThan(before); assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.ADMITTED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        STATES.put(payment.getPaymentId(),"PAID"); advance(5); worker.execute(command);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PAID); assertThat(countOutbox()).isEqualTo(1);
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
    }
    @Test void anOrderExpiryReceiptClosesAQueuedUnadmittedApprovalWithoutPgTraffic() {
        Payment payment=purchase(); String command=id(); submit(payment,command); recordExpiry(payment);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REQUESTED);
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
    @Test void anExpiryNoticeCannotEraseAnAlreadyObservedUnadmittedFinancialAnomaly() {
        Payment payment=purchase(); String command=id(); submit(payment,command); STATES.put(payment.getPaymentId(),"PAID"); worker.execute(command);
        recordExpiry(payment); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING); assertThat(GETS.get()).isGreaterThan(before);
        assertThat(row(command).getProtectedPayload()).isNotNull(); assertThat(BODIES).isEmpty(); assertThat(countOutbox()).isZero();
    }
    @Test void aConflictingExpiryAndAdmissionStopFurtherPostsWhileLookupContinues() {
        Payment payment=purchase(); String command=id(); submit(payment,command); FIRST_UNAVAILABLE.set(true); worker.execute(command);
        recordExpiry(payment); advance(5); int before=GETS.get(); worker.execute(command);
        assertThat(row(command).getStatus()).isEqualTo(PaymentApprovalStatus.REVIEW_REQUIRED);
        assertThat(row(command).getAdmittedAt()).isNotNull(); assertThat(stored(payment).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(GETS.get()).isGreaterThan(before); assertThat(BODIES).hasSize(1); assertThat(countOutbox()).isZero();
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
                    "amount",Map.of("total",amount,"paid","PAID".equals(state)?amount:0,"discount",0,"cancelled",0),
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
