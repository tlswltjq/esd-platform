package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.order.core.domain.LicenseGrant;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderRequest;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.domain.PublishedProduct;
import com.stove.order.core.domain.Refund;
import com.stove.order.core.domain.RefundAttemptRepository;
import com.stove.order.core.domain.RefundRepository;
import com.stove.order.core.domain.RefundReviewRoundRepository;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyLicenseGrant;
import com.stove.order.core.service.ReadLicenseGrantDelays;
import com.stove.order.core.service.RequestRefund;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * 결정 125: 지급 확인(LicenseGranted)을 기다리다 멈춘 환불을 Order가 진입 시각으로 감지한다.
 * 실제 MySQL에 저장된 진입 시각으로 게이지 집계·고객 processingNotice·ADMIN 목록/상세를 확인한다.
 * Mock·Spy 구성은 RefundDelayNoticeIntegrationTest와 같게 두어 캐시된 컨텍스트를 함께 쓴다.
 */
class LicenseGrantWaitIntegrationTest extends OrderIntegrationTestBase {
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant BOUNDARY = BASE.plus(Duration.ofMinutes(10));
    private static final AtomicLong PRODUCTS = new AtomicLong(5000);
    @MockitoSpyBean OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired RequestRefund request;
    @Autowired ApplyLicenseGrant grant;
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
        when(clock.instant()).thenReturn(BASE);
        jdbc.update("DELETE FROM order_refund_attempt ORDER BY id DESC"); jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund"); jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event"); jdbc.update("DELETE FROM processed_event");
    }

    @Test void anAutomaticRefundWithoutAGrantIsCountedAndNoticedExactlyFromTheBoundaryAndReadsDoNotChangeState() {
        Fixture f = waiting();
        assertThat(stored(f)).isEqualTo(BASE);
        when(clock.instant()).thenReturn(BOUNDARY.minusNanos(1000));
        assertThat(delays.count()).isZero();
        assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();

        when(clock.instant()).thenReturn(BOUNDARY);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(delays.count()).isEqualTo(1);
        Response result = customer(f);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.data().path("status").asText()).isEqualTo("PROCESSING");
        JsonNode notice = result.data().path("processingNotice");
        assertThat(notice.size()).isEqualTo(3);
        assertThat(notice.path("code").asText()).isEqualTo("LICENSE_GRANT_CONFIRMATION_DELAYED");
        assertThat(notice.path("since").asText()).isEqualTo(BOUNDARY.toString());
        assertThat(notice.path("message").asText()).isEqualTo("환불 처리 준비가 지연되고 있습니다. 처리를 계속 확인 중이며, 별도 재신청은 필요하지 않습니다.");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void theAdminListAndDetailShowTheStoredWaitStart() {
        Fixture f = waiting(); waiting();
        when(clock.instant()).thenReturn(BOUNDARY);
        Response detail = http("GET", "/api/v1/admin/refunds/" + f.refund().getRefundId(), "usr_admin", "ADMIN");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.data().path("licenseGrantWaitStartedAt").asText()).isEqualTo(BASE.toString());
        Response list = http("GET", "/api/v1/admin/refunds?status=PENDING_LICENSE_GRANT", "usr_admin", "ADMIN");
        assertThat(list.status()).isEqualTo(200);
        JsonNode content = list.data().path("content");
        assertThat(content.size()).isEqualTo(2);
        for (JsonNode row : content) assertThat(row.path("licenseGrantWaitStartedAt").asText()).isEqualTo(BASE.toString());
        assertThat(http("GET", "/api/v1/admin/refunds/" + f.refund().getRefundId(), "usr_buyer", "MEMBER").status()).isEqualTo(403);
    }

    @Test void aLateGrantEndsTheCountAndTheNoticeButKeepsTheStartForOperators() {
        Fixture f = waiting();
        when(clock.instant()).thenReturn(BOUNDARY.plusSeconds(60));
        assertThat(delays.count()).isEqualTo(1);
        grant.execute(new LicenseGrant(id(), BOUNDARY.plusSeconds(60), id(), f.order().getOrderNo(), "usr_buyer",
                f.order().getProductId(), f.order().getProductCode(), f.order().getPaymentId()));
        assertThat(refunds.findByOrderNo(f.order().getOrderNo()).orElseThrow().getStatus().name()).isEqualTo("PENDING_LICENSE_SUSPENSION");
        assertThat(delays.count()).isZero();
        assertThat(customer(f).data().path("processingNotice").isNull()).isTrue();
        assertThat(stored(f)).isEqualTo(BASE);
        assertThat(http("GET", "/api/v1/admin/refunds/" + f.refund().getRefundId(), "usr_admin", "ADMIN").data()
                .path("licenseGrantWaitStartedAt").asText()).isEqualTo(BASE.toString());
    }

    @Test void anApprovedReviewStartsTheWaitAtTheDecisionTime() {
        Order order = paid(BASE.minusSeconds(18000));
        Refund pending = request.execute("usr_buyer", order.getOrderNo());
        assertThat(stored(order)).isNull();
        Instant decided = BASE.plusSeconds(300);
        when(clock.instant()).thenReturn(decided);
        Response decision = send("POST", "/api/v1/admin/refunds/" + pending.getRefundId() + "/decision",
                Map.of("requestId", id(), "decision", "APPROVED", "reason", "실행 불가 확인"), token("usr_admin", "ADMIN"));
        assertThat(decision.status()).isEqualTo(200);
        assertThat(stored(order)).isEqualTo(decided);
        when(clock.instant()).thenReturn(decided.plus(Duration.ofMinutes(10)).minusNanos(1000));
        assertThat(delays.count()).isZero();
        when(clock.instant()).thenReturn(decided.plus(Duration.ofMinutes(10)));
        assertThat(delays.count()).isEqualTo(1);
    }

    private Fixture waiting() {
        Order order = paid(BASE.minusSeconds(3600));
        return new Fixture(order, request.execute("usr_buyer", order.getOrderNo()));
    }

    private Order paid(Instant resultAt) {
        String code = "Game-" + id();
        Order order = Order.create("usr_buyer", new OrderRequest(id(), code, 3, 10000),
                new PublishedProduct(PRODUCTS.incrementAndGet(), code, "지급 확인 대기 검증 상품", 3, 10000, "KRW"), id());
        ReflectionTestUtils.setField(order, "createdAt", resultAt.minusSeconds(60));
        order.apply(new PaymentResult(id(), resultAt, order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), "KRW", OrderStatus.PAID, null, null));
        return orders.saveAndFlush(order);
    }

    private Instant stored(Fixture f) { return stored(f.order()); }
    private Instant stored(Order order) {
        Timestamp value = jdbc.queryForObject("SELECT license_grant_wait_started_at FROM order_refund WHERE order_no=?", Timestamp.class, order.getOrderNo());
        return value == null ? null : value.toInstant();
    }
    private Response customer(Fixture f) { return http("GET", "/api/v1/orders/" + f.order().getOrderNo() + "/refund", "usr_buyer", "MEMBER"); }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("purchase_order", "order_refund", "order_refund_review_round", "order_license_grant", "order_refund_attempt", "outbox_event", "processed_event"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }
    private Response http(String method, String path, String subject, String role) { return send(method, path, null, token(subject, role)); }
    private Response send(String method, String path, Object body, String bearer) {
        RestClient.RequestBodySpec spec = RestClient.create("http://127.0.0.1:" + port).method(HttpMethod.valueOf(method)).uri(path);
        spec.header("Authorization", "Bearer " + bearer);
        if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((sent, reply) -> new Response(reply.getStatusCode().value(), mapper.readTree(reply.getBody())));
    }
    private record Fixture(Order order, Refund refund) { }
    private record Response(int status, JsonNode body) { JsonNode data() { return body.path("data"); } }
}
