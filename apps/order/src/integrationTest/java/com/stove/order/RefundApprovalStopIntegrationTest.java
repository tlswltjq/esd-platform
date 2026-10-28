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
import com.stove.order.core.service.RequestRefundApprovalStop;
import com.stove.order.core.service.ApplyApprovalCorrection;
import com.stove.order.core.domain.ApprovalCorrectionConfirmation;
import com.stove.order.core.domain.RefundApprovalStop;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectionRequestedEvent;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.stove.order.core.service.RequestRefund;
import com.stove.order.core.port.CatalogProducts;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.payload.LicenseApprovalCorrectedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
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

class RefundApprovalStopIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final AtomicLong PRODUCTS = new AtomicLong(1000);
    @MockitoSpyBean OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RequestRefund request;
    @Autowired DecideRefund decide;
    @Autowired RequestRefundApprovalStop stop;
    @Autowired ApplyApprovalCorrection correction;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaTemplate<String,String> kafka;
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
        listeners.stop(); reset(orders, rounds, outbox);
        when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC"); jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
    }

    @Test void stoppingBeforeSuspensionImmediatelyOpensACorrectionRoundAndRetainsTheOriginalReceipt() {
        Fixture fixture=approved(false); RefundReviewRequest originalDecision=initialDecision(fixture); String command=id();
        Response receipt=stop(fixture,command,fixture.refund().getRefundId(),"admin");
        assertThat(receipt.status()).isEqualTo(202); assertThat(receipt.cacheControl()).contains("no-store"); assertThat(receipt.location()).isEqualTo(adminPath(fixture));
        Refund current=current(fixture); assertThat(current.getStatus().name()).isEqualTo("PENDING_REVIEW");
        assertThat(current.getCurrentReviewId()).isNotEqualTo(fixture.refund().getRefundId()); assertThat(current.reviewReceipt()).isEqualTo(fixture.refund().reviewReceipt());
        assertThat(customer(fixture).data().path("currentReview").path("origin").asText()).isEqualTo("APPROVAL_CORRECTION");
        Map<String,List<Map<String,Object>>> after=snapshot();
        assertThat(stop(fixture,command,fixture.refund().getRefundId(),"admin").data()).isEqualTo(receipt.data());
        assertThat(stop(fixture,id(),fixture.refund().getRefundId(),"admin").status()).isEqualTo(409);
        assertThat(stop(fixture,command,fixture.refund().getRefundId(),"another-admin").status()).isEqualTo(409);
        assertThat(initialVerdict(fixture,originalDecision.requestId(),"APPROVED",originalDecision.reason(),originalDecision.operatorSubject()).status()).isEqualTo(200);
        assertThat(snapshot()).isEqualTo(after); assertThat(current.getReceivedAt()).isEqualTo(BASE);
        grant.execute(grantInput(fixture)); assertThat(outboxCount("LicenseSuspensionRequested")).isZero();
        assertThat(verdict(fixture,current.getCurrentReviewId(),id(),"REJECTED","재검토 결과 반려","admin").status()).isEqualTo(200);
        assertThat(customer(fixture).data().path("rejectionReason").asText()).isEqualTo("재검토 결과 반려");
    }

    @Test void lateSuspensionRequestsOnlyRestorationAndActualRestorationOpensTheNewRound() throws Exception {
        Fixture fixture=approved(true); String review=fixture.refund().getRefundId(); String command=id();
        assertThat(stop(fixture,command,review,"admin").status()).isEqualTo(202);
        assertThat(current(fixture).getStatus().name()).isEqualTo("PENDING_APPROVAL_STOP"); assertThat(rounds.count()).isEqualTo(1);
        assertThat(customer(fixture).data().path("status").asText()).isEqualTo("PROCESSING");
        assertThat(verdict(fixture,review,id(),"REJECTED","복구 전 판정","admin").status()).isEqualTo(409);
        LicenseSuspensionConfirmation suspended=suspensionInput(fixture); when(clock.instant()).thenReturn(BASE.plusSeconds(2)); suspension.execute(suspended);
        assertThat(attempts.count()).isZero(); assertThat(outboxCount("RefundExecutionRequested")).isZero(); assertThat(outboxCount("LicenseApprovalCorrectionRequested")).isEqualTo(1);
        RefundReviewRound round=rounds.findByReviewId(review).orElseThrow(); RefundApprovalStop audit=round.getApprovalStop();
        LicenseApprovalCorrectionRequestedEvent expected=new LicenseApprovalCorrectionRequestedEvent(audit.getRestorationRequestId(),BASE.plusSeconds(2),fixture.refund().getRefundId(),review,command,
                suspended.licenseId(),suspended.orderNo(),suspended.buyerSubject(),suspended.productId(),suspended.productCode(),suspended.paymentId(),suspended.requestEventId(),suspended.eventId());
        Map<String,Object> row=jdbc.queryForMap("SELECT aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status FROM outbox_event WHERE event_type='LicenseApprovalCorrectionRequested'");
        assertThat(row).containsEntry("aggregate_type","Order").containsEntry("aggregate_id",fixture.order().getOrderNo()).containsEntry("topic",expected.topic())
                .containsEntry("event_type",expected.eventType()).containsEntry("partition_key",expected.partitionKey()).containsEntry("status","PENDING");
        assertThat(mapper.readTree(row.get("payload").toString())).isEqualTo(mapper.readTree(mapper.writeValueAsString(expected)));
        suspension.execute(suspended); assertThat(outboxCount("LicenseApprovalCorrectionRequested")).isEqualTo(1);
        ApprovalCorrectionConfirmation restored=restored(fixture,suspended); when(clock.instant()).thenReturn(BASE.plusSeconds(3)); correction.execute(restored);
        assertThat(current(fixture).getStatus().name()).isEqualTo("PENDING_REVIEW"); assertThat(current(fixture).getSuspensionRequestEventId()).isNull();
        String next=current(fixture).getCurrentReviewId(); assertThat(next).isNotEqualTo(review); assertThat(customer(fixture).data().path("currentReview").path("reviewNo").asInt()).isEqualTo(2);
        Map<String,List<Map<String,Object>>> after=snapshot(); correction.execute(restored); suspension.execute(suspended);
        assertThat(snapshot()).isEqualTo(after); assertThat(stop(fixture,command,review,"admin").status()).isEqualTo(202);
        assertThat(verdict(fixture,next,id(),"APPROVED","다시 확인한 승인","admin").status()).isEqualTo(200);
        String newSuspension=current(fixture).getSuspensionRequestEventId(); assertThat(newSuspension).isNotEqualTo(suspended.requestEventId());
        after=snapshot(); correction.execute(restored); suspension.execute(suspended); assertThat(snapshot()).isEqualTo(after);
        when(clock.instant()).thenReturn(BASE.plusSeconds(4)); suspension.execute(new LicenseSuspensionConfirmation(id(),BASE.plusSeconds(4),newSuspension,fixture.refund().getRefundId(),suspended.licenseId(),
                suspended.orderNo(),suspended.buyerSubject(),suspended.productId(),suspended.productCode(),suspended.paymentId()));
        assertThat(attempts.count()).isEqualTo(1); assertThat(outboxCount("RefundExecutionRequested")).isEqualTo(1);
        assertThat(stop(fixture,id(),next,"admin").status()).isEqualTo(409);
    }

    @Test void storedExecutionClosesTheWindowEvenIfThePaymentRequestIsStillInTheOutbox() {
        Fixture fixture=approved(true); when(clock.instant()).thenReturn(BASE.plusSeconds(2)); suspension.execute(suspensionInput(fixture));
        Map<String,List<Map<String,Object>>> before=snapshot(); assertThat(stop(fixture,id(),fixture.refund().getRefundId(),"admin").status()).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before); assertThat(outboxCount("RefundExecutionRequested")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_type='RefundExecutionRequested'",String.class)).isEqualTo("PENDING");
    }

    @Test void stopAndSuspensionRaceHasExactlyOneOutcomeWithoutAStopAndPaymentExecutionTogether() throws Exception {
        Fixture fixture=approved(true); LicenseSuspensionConfirmation suspended=suspensionInput(fixture); when(clock.instant()).thenReturn(BASE.plusSeconds(2));
        List<Response> responses=race(() -> stop(fixture,id(),fixture.refund().getRefundId(),"admin"),()-> { suspension.execute(suspended); return new Response(200,null,null,mapper.createObjectNode()); });
        int status=responses.getFirst().status(); assertThat(status).isIn(202,409);
        assertThat(outboxCount("RefundExecutionRequested")).isEqualTo(status==202 ? 0:1);
        assertThat(outboxCount("LicenseApprovalCorrectionRequested")).isEqualTo(status==202 ? 1:0);
        assertThat(attempts.count()).isEqualTo(status==202 ? 0:1);
    }

    @Test void concurrentIdenticalStopsReturnOneReceiptAndOneNewReview() throws Exception {
        Fixture fixture=approved(false); String command=id(); List<Response> result=race(()->stop(fixture,command,fixture.refund().getRefundId(),"admin"),()->stop(fixture,command,fixture.refund().getRefundId(),"admin"));
        assertThat(result.stream().map(Response::status)).containsExactly(202,202); assertThat(result.getFirst().data()).isEqualTo(result.getLast().data()); assertThat(rounds.count()).isEqualTo(2);
    }

    @Test void differentConcurrentStopsCannotCreateTwoCorrectionRounds() throws Exception {
        Fixture fixture=approved(false); List<Response> result=race(()->stop(fixture,id(),fixture.refund().getRefundId(),"admin"),()->stop(fixture,id(),fixture.refund().getRefundId(),"admin"));
        assertThat(result.stream().map(Response::status)).containsExactlyInAnyOrder(202,409); assertThat(rounds.count()).isEqualTo(2);
    }

    @Test void aGlobalStopCommandCollisionRollsBackTheLosingRefundAndReview() throws Exception {
        Fixture first=approved(false); Fixture second=approved(false); String command=id(); CountDownLatch reads=new CountDownLatch(2);
        doAnswer(call->{ Optional<RefundReviewRound> result=entityManager.createQuery("select r from RefundReviewRound r where r.approvalStop.requestId=:id",RefundReviewRound.class)
                .setParameter("id",command).getResultList().stream().findFirst(); reads.countDown(); await(reads); return result;
        }).when(rounds).findByApprovalStop_RequestId(command);
        try {
            List<Response> result=race(()->stop(first,command,first.refund().getRefundId(),"admin"),()->stop(second,command,second.refund().getRefundId(),"admin"));
            assertThat(result.stream().map(Response::status)).containsExactlyInAnyOrder(202,409); assertThat(rounds.count()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund WHERE status='PENDING_LICENSE_GRANT'",Integer.class)).isEqualTo(1);
        } finally { reset(rounds); }
    }

    @Test void stopAuditPointerAndNewReviewRollbackAfterActualFlushAndTheCommandCanBeRetried() {
        Fixture fixture=approved(false); String command=id(); Map<String,List<Map<String,Object>>> before=snapshot();
        doAnswer(call->{ entityManager.flush(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round",Integer.class)).isEqualTo(2);
            throw new IllegalStateException("after stop audit and new review flush"); }).when(rounds).flush();
        try { assertThat(stop(fixture,command,fixture.refund().getRefundId(),"admin").status()).isEqualTo(500); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(rounds); }
        assertThat(stop(fixture,command,fixture.refund().getRefundId(),"admin").status()).isEqualTo(202);
    }

    @Test void suspensionInboxAndRestorationOutboxRollbackTogether() {
        Fixture fixture=approved(true); stop(fixture,id(),fixture.refund().getRefundId(),"admin"); LicenseSuspensionConfirmation input=suspensionInput(fixture);
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); Map<String,List<Map<String,Object>>> before=snapshot();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> doAnswer(call->{ call.callRealMethod(); entityManager.flush(); throw new IllegalStateException("after restoration outbox flush"); }).when(outbox).record(anyString(),anyString(),any(DomainEvent.class)));
        try { assertThatThrownBy(()->suspension.execute(input)).isInstanceOf(IllegalStateException.class); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(outbox); }
        suspension.execute(input); assertThat(outboxCount("LicenseApprovalCorrectionRequested")).isEqualTo(1);
    }

    @Test void restorationInboxAndNewReviewRollbackTogether() {
        Fixture fixture=approved(true); stop(fixture,id(),fixture.refund().getRefundId(),"admin"); LicenseSuspensionConfirmation suspended=suspensionInput(fixture);
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); suspension.execute(suspended); ApprovalCorrectionConfirmation input=restored(fixture,suspended); when(clock.instant()).thenReturn(BASE.plusSeconds(3));
        Map<String,List<Map<String,Object>>> before=snapshot();
        doAnswer(call->{ RefundReviewRound next=call.getArgument(0,RefundReviewRound.class); entityManager.persist(next); entityManager.flush(); throw new IllegalStateException("after restored audit and new review flush"); }).when(rounds).save(any(RefundReviewRound.class));
        try { assertThatThrownBy(()->correction.execute(input)).isInstanceOf(IllegalStateException.class); assertThat(snapshot()).isEqualTo(before); }
        finally { reset(rounds); }
        correction.execute(input); assertThat(current(fixture).getStatus().name()).isEqualTo("PENDING_REVIEW");
    }

    @Test void invalidTargetsPayloadsAndRolesCannotMutateAnyRefundState() {
        Fixture fixture=approved(false); Map<String,List<Map<String,Object>>> before=snapshot(); String path=adminPath(fixture)+"/approval-stops";
        Map<String,String> body=Map.of("requestId",id(),"reviewId",fixture.refund().getRefundId(),"reason","잘못된 승인 확인");
        assertThat(http("POST",path,body,"admin",null).status()).isEqualTo(401);
        for (String role:List.of("MEMBER","CREATOR","REVIEWER")) assertThat(http("POST",path,body,"actor",role).status()).isEqualTo(403);
        for (String reason:List.of(""," ","x".repeat(501))) assertThat(http("POST",path,Map.of("requestId",id(),"reviewId",fixture.refund().getRefundId(),"reason",reason),"admin","ADMIN").status()).isEqualTo(400);
        assertThat(http("POST",path,Map.of(),"admin","ADMIN").status()).isEqualTo(400);
        assertThat(stop(fixture,id(),id(),"admin").status()).isEqualTo(404);
        assertThat(http("GET",path,null,"admin","ADMIN").status()).isEqualTo(403);
        assertThat(snapshot()).isEqualTo(before);
        Fixture unapproved=pending(); assertThat(stop(unapproved,id(),unapproved.refund().getRefundId(),"admin").status()).isEqualTo(409);
        Fixture rejected=rejected(); assertThat(stop(rejected,id(),rejected.refund().getRefundId(),"admin").status()).isEqualTo(409);
        Fixture automatic=new Fixture(paid(),null); when(clock.instant()).thenReturn(BASE.minusSeconds(17000)); Refund auto=request.execute("usr_buyer",automatic.order().getOrderNo());
        when(clock.instant()).thenReturn(BASE.plusSeconds(1)); assertThat(stop(new Fixture(automatic.order(),auto),id(),auto.getRefundId(),"admin").status()).isEqualTo(404);
    }

    @Test void customerHistoryShowsTheCorrectionWithoutExposingAdministratorStopDetails() throws Exception {
        Fixture fixture=approved(false); stop(fixture,id(),fixture.refund().getRefundId(),"secret-admin");
        Response customer=http("GET",path(fixture)+"/reviews",null,"usr_buyer","MEMBER"); assertThat(customer.status()).isEqualTo(200);
        String payload=mapper.writeValueAsString(customer.body()); assertThat(payload).doesNotContain("secret-admin","잘못된 승인 확인","operatorSubject","stopRequestId","correctionRequestId","\"approvalStop\":");
        Response admin=http("GET",adminPath(fixture)+"/reviews",null,"admin","ADMIN"); assertThat(mapper.writeValueAsString(admin.body())).contains("secret-admin","잘못된 승인 확인","approvalStop");
        assertThat(http("GET",path(fixture)+"/reviews",null,"other-buyer","MEMBER").status()).isEqualTo(404);
    }

    @Test void wrongRestorationEvidenceAndChangedSuspensionDuplicatesLeaveNoChanges() {
        Fixture fixture=approved(true); stop(fixture,id(),fixture.refund().getRefundId(),"admin"); LicenseSuspensionConfirmation suspended=suspensionInput(fixture);
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); suspension.execute(suspended); ApprovalCorrectionConfirmation good=restored(fixture,suspended); Map<String,List<Map<String,Object>>> before=snapshot();
        ApprovalCorrectionConfirmation bad=new ApprovalCorrectionConfirmation(good.eventId(),good.occurredAt(),good.requestEventId(),good.refundId(),good.reviewId(),id(),good.licenseId(),good.orderNo(),good.buyerSubject(),good.productId(),good.productCode(),good.paymentId(),good.suspensionRequestEventId(),good.suspensionEventId());
        assertThatThrownBy(()->correction.execute(bad)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->suspension.execute(new LicenseSuspensionConfirmation(suspended.eventId(),suspended.occurredAt().plusSeconds(1),suspended.requestEventId(),suspended.refundId(),suspended.licenseId(),suspended.orderNo(),suspended.buyerSubject(),suspended.productId(),suspended.productCode(),suspended.paymentId()))).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void realKafkaDeliversTheRestorationCompletionAndDuplicateOpensOnlyOneReview() throws Exception {
        Fixture fixture=approved(true); stop(fixture,id(),fixture.refund().getRefundId(),"admin"); LicenseSuspensionConfirmation suspended=suspensionInput(fixture);
        when(clock.instant()).thenReturn(BASE.plusSeconds(2)); suspension.execute(suspended); ApprovalCorrectionConfirmation input=restored(fixture,suspended); when(clock.instant()).thenReturn(BASE.plusSeconds(3));
        LicenseApprovalCorrectedEvent event=new LicenseApprovalCorrectedEvent(input.eventId(),input.occurredAt(),input.requestEventId(),input.refundId(),input.reviewId(),input.stopRequestId(),
                input.licenseId(),input.orderNo(),input.buyerSubject(),input.productId(),input.productCode(),input.paymentId(),input.suspensionRequestEventId(),input.suspensionEventId());
        listeners.start();
        for (int index=0;index<2;index++) {
            ProducerRecord<String,String> record=new ProducerRecord<>(event.topic(),event.partitionKey(),mapper.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID,event.eventId().getBytes(StandardCharsets.UTF_8)); record.headers().add(EventHeaders.EVENT_TYPE,event.eventType().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get(10,TimeUnit.SECONDS);
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(()->{
            assertThat(current(fixture).getStatus().name()).isEqualTo("PENDING_REVIEW"); assertThat(rounds.count()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group='order-approval-correction'",Integer.class)).isEqualTo(1);
        });
    }

    @Test void correctionRoundsCanBeStoppedAgainAndTheirRejectionsStillAllowCustomerReconsideration() {
        Fixture fixture=approved(false); String firstCommand=id(); stop(fixture,firstCommand,fixture.refund().getRefundId(),"admin");
        String second=current(fixture).getCurrentReviewId(); String verdictCommand=id();
        Response approved=verdict(fixture,second,verdictCommand,"APPROVED","다시 승인","admin"); assertThat(approved.status()).isEqualTo(200);
        assertThat(stop(fixture,id(),second,"admin").status()).isEqualTo(202);
        String third=current(fixture).getCurrentReviewId(); Map<String,List<Map<String,Object>>> before=snapshot();
        assertThat(verdict(fixture,second,verdictCommand,"APPROVED","다시 승인","admin").data()).isEqualTo(approved.data());
        assertThat(stop(fixture,firstCommand,fixture.refund().getRefundId(),"admin").status()).isEqualTo(202); assertThat(snapshot()).isEqualTo(before);
        assertThat(verdict(fixture,third,id(),"REJECTED","재검토 결과 반려","admin").status()).isEqualTo(200);
        Response application=reconsider(fixture,id(),third,"새 오류 근거"); assertThat(application.status()).isEqualTo(202);
        assertThat(application.data().path("reviewNo").asInt()).isEqualTo(4); assertThat(current(fixture).getReceivedAt()).isEqualTo(BASE);
    }

    private Fixture approved(boolean granted) {
        Fixture fixture=pending(); if (granted) grant.execute(grantInput(fixture));
        decide.execute(new RefundReviewRequest(id(),fixture.refund().getRefundId(),"usr_admin",RefundReviewDecision.APPROVED,"승인 근거"));
        when(clock.instant()).thenReturn(BASE.plusSeconds(1)); return new Fixture(fixture.order(),current(fixture));
    }
    private RefundReviewRequest initialDecision(Fixture fixture) {
        return new RefundReviewRequest(fixture.refund().reviewReceipt().requestId(),fixture.refund().getRefundId(),"usr_admin",RefundReviewDecision.APPROVED,"승인 근거");
    }
    private Response stop(Fixture fixture,String command,String review,String actor) {
        return http("POST",adminPath(fixture)+"/approval-stops",Map.of("requestId",command,"reviewId",review,"reason","잘못된 승인 확인"),actor,"ADMIN");
    }
    private LicenseSuspensionConfirmation suspensionInput(Fixture fixture) {
        LicenseGrant license=grantInput(fixture);
        String licenseId=jdbc.queryForObject("SELECT license_id FROM order_license_grant WHERE order_no=?",String.class,fixture.order().getOrderNo());
        return new LicenseSuspensionConfirmation(id(),BASE.plusSeconds(1),current(fixture).getSuspensionRequestEventId(),fixture.refund().getRefundId(),licenseId,
                license.orderNo(),license.buyerSubject(),license.productId(),license.productCode(),license.paymentId());
    }
    private ApprovalCorrectionConfirmation restored(Fixture fixture,LicenseSuspensionConfirmation suspended) {
        RefundReviewRound round=rounds.findByReviewId(fixture.refund().getRefundId()).orElseThrow(); RefundApprovalStop audit=round.getApprovalStop();
        return new ApprovalCorrectionConfirmation(id(),BASE.plusSeconds(3),audit.getRestorationRequestId(),fixture.refund().getRefundId(),round.getReviewId(),audit.getRequestId(),
                suspended.licenseId(),suspended.orderNo(),suspended.buyerSubject(),suspended.productId(),suspended.productCode(),suspended.paymentId(),suspended.requestEventId(),suspended.eventId());
    }
    private int outboxCount(String type) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=?",Integer.class,type); }
    private Order paid() {
        String code = "Game-" + id();
        Order order = Order.create("usr_buyer", new OrderRequest(id(), code, 3, 10000),
                new PublishedProduct(PRODUCTS.incrementAndGet(), code, "승인 중단 검증 상품", 3, 10000, "KRW"), id());
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
