package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.LicenseRestorationConfirmation;
import com.stove.order.core.domain.LicenseSuspensionConfirmation;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundApprovalStopRequest;
import com.stove.order.core.domain.RefundAttempt;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundFailureConfirmation;
import com.stove.order.core.domain.RefundReconsiderationReceipt;
import com.stove.order.core.domain.RefundReconsiderationRequest;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundRetryRequest;
import com.stove.order.core.domain.RefundReviewDecision;
import com.stove.order.core.domain.RefundReviewRequest;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.domain.RefundRoundDecisionRequest;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ApplyRefundRestoration;
import com.stove.order.core.service.ApplyRefundSuspension;
import com.stove.order.core.service.DecideRefund;
import com.stove.order.core.service.DecideRefundReconsideration;
import com.stove.order.core.service.ReadLicenseGrantDelays;
import com.stove.order.core.service.RequestRefund;
import com.stove.order.core.service.RequestRefundApprovalStop;
import com.stove.order.core.service.RequestRefundReconsideration;
import com.stove.order.core.service.RequestRefundRetry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * 결정 125·129 범위 확장: 정지·복구·승인 중단 대기의 진입 시각을 실제 MySQL에 저장하고, 같은 10분 경계로
 * 게이지(status 라벨)·고객 processingNotice·ADMIN 목록/상세를 낸다. 진입 경로는 지급 확인·자동 접수·심사 승인·
 * 재심사 승인·재시도·확정 실패·승인 중단 전부다. Mock·Spy 구성은 LicenseGrantWaitIntegrationTest와 같게 두어 컨텍스트를 함께 쓴다.
 */
class RefundLicenseStepWaitIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Duration THRESHOLD = Duration.ofMinutes(10);
    private static final AtomicLong PRODUCTS = new AtomicLong(7000);
    @MockitoSpyBean OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RequestRefund request;
    @Autowired ApplyLicenseGrant grant;
    @Autowired ApplyRefundSuspension suspension;
    @Autowired ApplyRefundRestoration restoration;
    @Autowired RequestRefundRetry retry;
    @Autowired DecideRefund decide;
    @Autowired RequestRefundReconsideration reconsider;
    @Autowired DecideRefundReconsideration decideRound;
    @Autowired RequestRefundApprovalStop stop;
    @Autowired ReadLicenseGrantDelays delays;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean RefundAttemptRepository attempts;
    @MockitoSpyBean RefundReviewRoundRepository rounds;
    @MockitoSpyBean OutboxRecorder outbox;
    @MockitoBean Clock clock;
    @MockitoBean CatalogProducts catalog;
    @LocalServerPort int port;

    @BeforeEach @AfterEach void clear() {
        listeners.stop(); reset(orders, rounds, outbox, attempts);
        at(BASE);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC"); jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
    }

    /** 지급 확인(늦게 도착) → 정지 대기, 확정 실패 → 복구 대기, 재시도 → 정지 대기. 각 대기는 자기 진입 시각부터 센다. */
    @Test void grantFailureAndRetryEachStartTheirWaitAndTheGaugeNoticeAndAdminFollowTheBoundary() {
        Order order = paid(BASE.minusSeconds(3600));
        Refund refund = request.execute("usr_buyer", order.getOrderNo());
        Instant granted = BASE.plusSeconds(60);
        at(granted);
        LicenseGrant license = grant(order, granted);
        grant.execute(license);
        assertThat(status(order)).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(started(order, "license_suspension_wait_started_at")).isEqualTo(granted);

        assertDelayFollowsTheBoundary(order, refund, RefundStatus.PENDING_LICENSE_SUSPENSION, granted,
                "LICENSE_SUSPENSION_CONFIRMATION_DELAYED", "licenseSuspensionWaitStartedAt");

        Instant suspended = granted.plus(THRESHOLD).plusSeconds(60);
        at(suspended.plusSeconds(1));
        suspension.execute(new LicenseSuspensionConfirmation(id(), suspended, stored(order).getSuspensionRequestEventId(), refund.getRefundId(),
                license.licenseId(), order.getOrderNo(), order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId()));
        assertThat(status(order)).isEqualTo(RefundStatus.PENDING_PAYMENT_REFUND);
        assertThat(delays.count(RefundStatus.PENDING_LICENSE_SUSPENSION)).isZero();
        assertThat(started(order, "license_suspension_wait_started_at")).as("벗어나도 운영 조회용으로 남는다").isEqualTo(granted);

        RefundAttempt attempt = attempts.findByAttemptId(stored(order).getCurrentAttemptId()).orElseThrow();
        Instant failed = suspended.plusSeconds(10);
        at(failed);
        restoration.execute(new RefundFailureConfirmation(id(), suspended.plusSeconds(5), refund.getRefundId(), attempt.getAttemptId(),
                attempt.getExecutionRequestEventId(), order.getOrderNo(), order.getPaymentId(), order.getBuyerSubject(), order.getAmount(),
                order.getCurrency(), "pg-original", "cancel-own", "a".repeat(64)));
        assertThat(status(order)).isEqualTo(RefundStatus.PENDING_LICENSE_RESTORATION);
        assertThat(started(order, "license_restoration_wait_started_at")).isEqualTo(failed);
        assertDelayFollowsTheBoundary(order, refund, RefundStatus.PENDING_LICENSE_RESTORATION, failed,
                "LICENSE_RESTORATION_CONFIRMATION_DELAYED", "licenseRestorationWaitStartedAt");

        RefundAttempt failedAttempt = attempts.findByAttemptId(attempt.getAttemptId()).orElseThrow();
        Instant restored = failed.plus(THRESHOLD).plusSeconds(30);
        at(restored.plusSeconds(1));
        restoration.execute(new LicenseRestorationConfirmation(id(), restored, failedAttempt.getRestorationRequestEventId(), refund.getRefundId(),
                failedAttempt.getAttemptId(), failedAttempt.getFailureEventId(), failedAttempt.getLicenseId(), order.getOrderNo(), order.getBuyerSubject(),
                order.getProductId(), order.getProductCode(), order.getPaymentId(), failedAttempt.getSuspensionRequestEventId(),
                failedAttempt.getSuspensionEventId(), false));
        assertThat(status(order)).isEqualTo(RefundStatus.FAILED);
        assertThat(delays.count(RefundStatus.PENDING_LICENSE_RESTORATION)).isZero();

        Instant retried = restored.plusSeconds(600);
        at(retried);
        retry.execute(new RefundRetryRequest(order.getBuyerSubject(), order.getOrderNo(), attempt.getAttemptId()));
        assertThat(status(order)).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(started(order, "license_suspension_wait_started_at")).as("재시도는 정지 대기에 다시 들어간다").isEqualTo(retried);
        at(retried.plus(THRESHOLD).minusNanos(1000));
        assertThat(delays.count(RefundStatus.PENDING_LICENSE_SUSPENSION)).isZero();
        at(retried.plus(THRESHOLD));
        assertThat(delays.count(RefundStatus.PENDING_LICENSE_SUSPENSION)).isEqualTo(1);
    }

    @Test void anAutomaticReceiptWithAnExistingGrantStartsTheSuspensionWaitAtReceipt() {
        Order order = paid(BASE.minusSeconds(3600));
        at(BASE.minusSeconds(30));
        grant.execute(grant(order, BASE.minusSeconds(30)));
        at(BASE);
        request.execute("usr_buyer", order.getOrderNo());
        assertThat(status(order)).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(started(order, "license_suspension_wait_started_at")).isEqualTo(BASE);
    }

    @Test void reviewAndReconsiderationApprovalsWithAnExistingGrantStartTheSuspensionWaitAtTheDecision() {
        Order first = reviewed();
        Refund firstRefund = request.execute("usr_buyer", first.getOrderNo());
        Instant approved = BASE.plusSeconds(200);
        at(approved);
        decide.execute(new RefundReviewRequest(id(), firstRefund.getRefundId(), "usr_admin", RefundReviewDecision.APPROVED, "실행 불가 확인"));
        assertThat(status(first)).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(started(first, "license_suspension_wait_started_at")).isEqualTo(approved);

        Order second = reviewed();
        at(BASE);
        Refund secondRefund = request.execute("usr_buyer", second.getOrderNo());
        at(BASE.plusSeconds(60));
        decide.execute(new RefundReviewRequest(id(), secondRefund.getRefundId(), "usr_admin", RefundReviewDecision.REJECTED, "반려"));
        at(BASE.plusSeconds(120));
        RefundReconsiderationReceipt receipt = reconsider.execute(new RefundReconsiderationRequest(id(), second.getOrderNo(),
                secondRefund.getRefundId(), "usr_buyer", "재설치해도 실행 오류가 남았습니다"));
        assertThat(started(second, "license_suspension_wait_started_at")).isNull();
        Instant reconsidered = BASE.plusSeconds(300);
        at(reconsidered);
        decideRound.execute(new RefundRoundDecisionRequest(receipt.reviewId(), new RefundReviewRequest(id(), secondRefund.getRefundId(),
                "usr_admin", RefundReviewDecision.APPROVED, "재심사 승인")));
        assertThat(status(second)).isEqualTo(RefundStatus.PENDING_LICENSE_SUSPENSION);
        assertThat(started(second, "license_suspension_wait_started_at")).isEqualTo(reconsidered);
    }

    @Test void anApprovalStopStartsTheStopWaitAndTheGaugeNoticeAndAdminFollowTheBoundary() {
        Order order = reviewed();
        Refund refund = request.execute("usr_buyer", order.getOrderNo());
        at(BASE.plusSeconds(60));
        decide.execute(new RefundReviewRequest(id(), refund.getRefundId(), "usr_admin", RefundReviewDecision.APPROVED, "실행 불가 확인"));
        Instant stopped = BASE.plusSeconds(120);
        at(stopped);
        stop.execute(new RefundApprovalStopRequest(id(), refund.getRefundId(), refund.getRefundId(), "usr_admin", "자료를 잘못 읽어 재검토가 필요합니다"));
        assertThat(status(order)).isEqualTo(RefundStatus.PENDING_APPROVAL_STOP);
        assertThat(started(order, "approval_stop_wait_started_at")).isEqualTo(stopped);
        assertDelayFollowsTheBoundary(order, refund, RefundStatus.PENDING_APPROVAL_STOP, stopped,
                "APPROVAL_STOP_CONFIRMATION_DELAYED", "approvalStopWaitStartedAt");
    }

    private void assertDelayFollowsTheBoundary(Order order, Refund refund, RefundStatus status, Instant started, String code, String field) {
        Instant boundary = started.plus(THRESHOLD);
        at(boundary.minusNanos(1000));
        assertThat(delays.count(status)).isZero();
        assertThat(customer(order).data().path("processingNotice").isNull()).isTrue();

        at(boundary);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(delays.count(status)).isEqualTo(1);
        for (RefundStatus other : ReadLicenseGrantDelays.STATUSES) if (other != status) assertThat(delays.count(other)).as(other.name()).isZero();
        Response result = customer(order);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("status").asText()).isEqualTo("PROCESSING");
        JsonNode notice = result.data().path("processingNotice");
        assertThat(notice.size()).isEqualTo(3);
        assertThat(notice.path("code").asText()).isEqualTo(code);
        assertThat(notice.path("since").asText()).isEqualTo(boundary.toString());
        Response detail = http("/api/v1/admin/refunds/" + refund.getRefundId(), "usr_admin", "ADMIN");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().path(field).asText()).isEqualTo(started.toString());
        Response list = http("/api/v1/admin/refunds?status=" + status.name(), "usr_admin", "ADMIN");
        assertThat(list.data().path("content").size()).isEqualTo(1);
        assertThat(list.data().path("content").get(0).path(field).asText()).isEqualTo(started.toString());
        assertThat(snapshot()).as("조회는 상태를 바꾸지 않는다").isEqualTo(before);
    }

    private void at(Instant instant) { when(clock.instant()).thenReturn(instant); }

    private Order reviewed() {
        Order order = paid(BASE.minusSeconds(18000));
        at(BASE.minusSeconds(30));
        grant.execute(grant(order, BASE.minusSeconds(30)));
        at(BASE);
        return order;
    }

    private Order paid(Instant resultAt) {
        String code = "Game-" + id();
        Order order = Order.create("usr_buyer", new OrderRequest(id(), code, 3, 10000),
                new PublishedProduct(PRODUCTS.incrementAndGet(), code, "정지·복구 대기 검증 상품", 3, 10000, "KRW"), id());
        ReflectionTestUtils.setField(order, "createdAt", resultAt.minusSeconds(60));
        order.apply(new PaymentResult(id(), resultAt, order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return orders.saveAndFlush(order);
    }

    private static LicenseGrant grant(Order order, Instant at) {
        return new LicenseGrant(id(), at, id(), order.getOrderNo(), order.getBuyerSubject(), order.getProductId(), order.getProductCode(), order.getPaymentId());
    }

    private Refund stored(Order order) { return refunds.findByOrderNo(order.getOrderNo()).orElseThrow(); }
    private RefundStatus status(Order order) { return stored(order).getStatus(); }
    private Instant started(Order order, String column) {
        Timestamp value = jdbc.queryForObject("SELECT " + column + " FROM order_refund WHERE order_no=?", Timestamp.class, order.getOrderNo());
        return value == null ? null : value.toInstant();
    }
    private Response customer(Order order) { return http("/api/v1/orders/" + order.getOrderNo() + "/refund", "usr_buyer", "MEMBER"); }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("purchase_order", "order_refund", "order_refund_review_round", "order_license_grant", "order_refund_attempt", "outbox_event", "processed_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    private Response http(String path, String subject, String role) {
        return RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.GET).uri(path)
                .header("Authorization", "Bearer " + token(subject, role))
                .exchange((sent, reply) -> new Response(reply.getStatusCode().value(), mapper.readTree(reply.getBody())));
    }
    private record Response(int status, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
