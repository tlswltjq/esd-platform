package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.product;
import static com.stove.order.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.web.error.BusinessException;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderPaymentAdmission;
import com.stove.order.core.domain.OrderPaymentAdmissionRepository;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.domain.PaymentAdmissionInput;
import com.stove.order.core.domain.PaymentAdmissionFailureInput;
import com.stove.order.core.domain.PaymentResult;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyPaymentResult;
import com.stove.order.core.service.RequestOrderPaymentProcessing;
import com.stove.order.core.service.CreateOrder;
import com.stove.order.core.service.CloseExpiredOrder;
import com.stove.order.core.service.CloseExpiredOrders;
import com.stove.order.core.service.RecordOrderPaymentAttemptFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@TestPropertySource(properties = "stove.order.payment-service-key=development-payment-service-key")
class OrderPaymentLifecycleIntegrationTest extends OrderIntegrationTestBase {
    private static final String KEY = "development-payment-service-key";
    @Autowired CreateOrder create;
    @Autowired RequestOrderPaymentProcessing begin;
    @Autowired CloseExpiredOrder expire;
    @Autowired CloseExpiredOrders scan;
    @Autowired RecordOrderPaymentAttemptFailure fail;
    @Autowired ApplyPaymentResult apply;
    @Autowired OrderRepository orders;
    @Autowired OrderPaymentAdmissionRepository admissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxEventRepository outboxRows;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CatalogProducts catalog;
    @MockitoBean Clock clock;
    @LocalServerPort int port;

    @BeforeEach void prepare() {
        clean();
        when(catalog.detail(anyString())).thenReturn(product());
    }
    @AfterEach void clean() {
        jdbc.update("DELETE FROM order_payment_admission");
        jdbc.update("DELETE FROM order_refund_attempt");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test void internalAdmissionAndReplayReturnTheFirstReceiptEvenAfterTheDeadline() {
        Order order = saved("usr_buyer");
        PaymentAdmissionInput input = input(order);
        Response first = post(order.getOrderNo(), body(input), KEY, null);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.cacheControl()).isEqualTo("no-store");
        assertThat(first.body().path("data").path("expiresAt").asText()).isEqualTo(order.getExpiresAt().toString());
        when(clock.instant()).thenReturn(order.getExpiresAt().plusSeconds(10));
        Response replay = post(order.getOrderNo(), body(input), KEY, null);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(admissions.count()).isEqualTo(1);
    }

    @Test void expiryCommitsBeforeTheHttpRejectionAndReleasesThePurchaseSlot() {
        Order order = saved("usr_buyer");
        when(clock.instant()).thenReturn(order.getExpiresAt());
        Response response = post(order.getOrderNo(), body(input(order)), KEY, null);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().path("error").path("code").asText()).isEqualTo("ORDER_EXPIRED");
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderExpired'")).isEqualTo(1);
        assertThat(post(order.getOrderNo(),body(input(order)),KEY,null).status()).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderExpired'")).isEqualTo(1);
        assertThat(admissions.count()).isZero();
        Order next = create.execute("usr_buyer", request());
        assertThat(next.getOrderNo()).isNotEqualTo(order.getOrderNo());
        assertThat(next.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test void processingBlocksNewPurchasesInBothTheServiceAndTheDatabase() {
        Order order = saved("usr_buyer");
        begin.execute(input(order)).orElseThrow();
        assertThatThrownBy(() -> create.execute("usr_buyer", request())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO purchase_order(order_no,buyer_subject,request_id,product_id,product_code,title,content_version,list_amount,discount_amount,amount,currency,status,request_event_id,created_at,updated_at) "
                + "SELECT ?,buyer_subject,?,product_id,product_code,title,content_version,list_amount,discount_amount,amount,currency,'PENDING',?,created_at,updated_at FROM purchase_order WHERE order_no=?",
                id(), id(), id(), order.getOrderNo())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test void anAdmittedPaymentCompletesAfterThirtyMinutesAndPublishesThePaidFact() {
        Order order = saved("usr_buyer");
        PaymentAdmissionInput input = input(order);
        OrderPaymentAdmission receipt = begin.execute(input).orElseThrow();
        when(clock.instant()).thenReturn(order.getExpiresAt().plusSeconds(10));
        assertThat(expire.execute(order.getOrderNo())).isFalse();
        PaymentResult result = paid(order, input.paymentId(), order.getExpiresAt().plusSeconds(10));
        apply.execute(EventType.ORDER_PAYMENT_COMPLETED, result);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stored(order).getAmount()).isEqualTo(order.getAmount());
        assertThat(begin.execute(input).orElseThrow().getAdmittedAt()).isEqualTo(receipt.getAdmittedAt());
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderPaid'")).isEqualTo(1);
    }

    @Test void anUnadmittedResultForAnExpiredOrderRollsBackInboxAndDoesNotGrantAPurchase() {
        Order order = saved("usr_buyer");
        when(clock.instant()).thenReturn(order.getExpiresAt());
        assertThat(expire.execute(order.getOrderNo())).isTrue();
        assertThatThrownBy(() -> apply.execute(EventType.ORDER_PAYMENT_COMPLETED,
                paid(order, id(), order.getExpiresAt().plusSeconds(1)))).isInstanceOf(BusinessException.class);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(count("SELECT COUNT(*) FROM processed_event")).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderPaid'")).isZero();
    }

    @Test void aMemberBearerOrWrongServiceKeyCannotAdmitAPayment() {
        Order order = saved("usr_buyer");
        Map<String, Object> body = body(input(order));
        assertThat(post(order.getOrderNo(), body, null, token("usr_buyer", "MEMBER")).status()).isIn(401, 403);
        assertThat(post(order.getOrderNo(), body, "wrong", null).status()).isIn(401, 403);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(admissions.count()).isZero();
    }

    @Test void changedBuyerOrRequestSourceIsRejectedWithoutExpiringTheOrder() {
        Order order = saved("usr_buyer");
        when(clock.instant()).thenReturn(order.getExpiresAt());
        PaymentAdmissionInput wrong = new PaymentAdmissionInput(id(), order.getOrderNo(), id(), id(),
                "other-buyer", order.getAmount(), order.getCurrency());
        assertThat(post(order.getOrderNo(), body(wrong), KEY, null).status()).isEqualTo(409);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(admissions.count()).isZero();
    }

    @Test void simultaneousIdenticalCommandsProduceOneAdmissionAndTheSameReceipt() throws Exception {
        Order order = saved("usr_buyer");
        PaymentAdmissionInput input = input(order);
        List<String> receipts = together(() -> begin.execute(input).orElseThrow().getAdmissionId(),
                () -> begin.execute(input).orElseThrow().getAdmissionId());
        assertThat(receipts).containsExactly(input.admissionId(), input.admissionId());
        assertThat(admissions.count()).isEqualTo(1);
    }

    @Test void simultaneousDifferentCommandsForOneOrderAdmitOnlyOne() throws Exception {
        Order order = saved("usr_buyer");
        List<Boolean> results = together(() -> admitOrConflict(input(order)), () -> admitOrConflict(input(order)));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(admissions.count()).isEqualTo(1);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
    }

    @Test void oneAdmissionIdCannotBeReusedConcurrentlyForDifferentOrders() throws Exception {
        Order first = saved("usr_buyer");
        Order second = saved("other-buyer");
        String admissionId = id();
        List<Boolean> results = together(() -> admitOrConflict(input(first, admissionId)),
                () -> admitOrConflict(input(second, admissionId)));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(admissions.count()).isEqualTo(1);
        assertThat(List.of(stored(first).getStatus(), stored(second).getStatus()))
                .containsExactlyInAnyOrder(OrderStatus.PAYMENT_PROCESSING, OrderStatus.PENDING);
    }

    @Test void aRealFlushedAdmissionAndStateChangeRollBackTogether() {
        Order order = saved("usr_buyer");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            begin.execute(input(order)).orElseThrow();
            assertThat(count("SELECT COUNT(*) FROM order_payment_admission")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE order_no=?", String.class, order.getOrderNo()))
                    .isEqualTo("PAYMENT_PROCESSING");
            throw new IllegalStateException("intentional transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored(order).getPaymentId()).isNull();
        assertThat(admissions.count()).isZero();
    }

    @Test void aChangedReplayAndAnInvalidInternalInputAreRejected() {
        Order order = saved("usr_buyer");
        PaymentAdmissionInput input = input(order);
        begin.execute(input).orElseThrow();
        PaymentAdmissionInput changed = new PaymentAdmissionInput(input.admissionId(), input.orderNo(),
                input.requestEventId(), id(), input.buyerSubject(), input.amount(), input.currency());
        assertThat(post(order.getOrderNo(), body(changed), KEY, null).status()).isEqualTo(409);
        assertThat(post("not-an-order-id", body(input), KEY, null).status()).isEqualTo(400);
        assertThat(admissions.count()).isEqualTo(1);
        assertThat(stored(order).getPaymentId()).isEqualTo(input.paymentId());
    }

    @Test void aVerifiedFailureReleasesOnlyItsAdmissionAndAnOldReplayCannotReleaseALaterOne() {
        Order order=saved("usr_buyer"); PaymentAdmissionInput first=input(order); begin.execute(first).orElseThrow();
        PaymentAdmissionFailureInput failed=new PaymentAdmissionFailureInput(first.admissionId(),order.getOrderNo(),first.paymentId(),"tx-first",order.getCreatedAt().plusSeconds(1));
        OrderPaymentAdmission closed=fail.execute(failed);
        assertThat(closed.getClosedOrderStatus()).isEqualTo("PENDING"); assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(stored(order).getPaymentId()).isNull();
        PaymentAdmissionInput second=new PaymentAdmissionInput(id(),order.getOrderNo(),order.getRequestEventId(),first.paymentId(),order.getBuyerSubject(),order.getAmount(),order.getCurrency());
        begin.execute(second).orElseThrow(); when(clock.instant()).thenReturn(order.getExpiresAt().plusSeconds(1));
        assertThat(fail.execute(failed).getClosedAt()).isEqualTo(closed.getClosedAt());
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(stored(order).getPaymentId()).isEqualTo(first.paymentId());
        assertThatThrownBy(()->fail.execute(new PaymentAdmissionFailureInput(first.admissionId(),order.getOrderNo(),first.paymentId(),"changed",failed.failedAt())))
                .isInstanceOf(BusinessException.class);
    }
    @Test void aVerifiedFailureAtTheDeadlineExpiresTheOrderAndReleasesThePurchaseSlot() {
        Order order=saved("usr_buyer"); PaymentAdmissionInput input=input(order); begin.execute(input).orElseThrow();
        when(clock.instant()).thenReturn(order.getExpiresAt());
        OrderPaymentAdmission closed=fail.execute(new PaymentAdmissionFailureInput(input.admissionId(),order.getOrderNo(),input.paymentId(),"tx-expired",order.getExpiresAt()));
        assertThat(closed.getClosedOrderStatus()).isEqualTo("EXPIRED"); assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(create.execute("usr_buyer",request()).getOrderNo()).isNotEqualTo(order.getOrderNo());
    }
    @Test void concurrentIdenticalFailureReportsReturnOneClosure() throws Exception {
        Order order=saved("usr_buyer"); PaymentAdmissionInput input=input(order); begin.execute(input).orElseThrow();
        PaymentAdmissionFailureInput failure=new PaymentAdmissionFailureInput(input.admissionId(),order.getOrderNo(),input.paymentId(),"tx-one",order.getCreatedAt().plusSeconds(1));
        List<Instant> closed=together(()->fail.execute(failure).getClosedAt(),()->fail.execute(failure).getClosedAt());
        assertThat(closed.get(0)).isEqualTo(closed.get(1)); assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(admissions.count()).isEqualTo(1);
    }
    @Test void aFlushedFailureClosureRollsBackWithItsOrderState() {
        Order order=saved("usr_buyer"); PaymentAdmissionInput input=input(order); begin.execute(input).orElseThrow();
        PaymentAdmissionFailureInput failure=new PaymentAdmissionFailureInput(input.admissionId(),order.getOrderNo(),input.paymentId(),"tx-rollback",order.getCreatedAt().plusSeconds(1));
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            fail.execute(failure); admissions.flush();
            assertThat(jdbc.queryForObject("SELECT closed_order_status FROM order_payment_admission WHERE admission_id=?",String.class,input.admissionId())).isEqualTo("PENDING");
            throw new IllegalStateException("rollback after actual failure flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(admissions.findByAdmissionId(input.admissionId()).orElseThrow().getClosedAt()).isNull();
    }
    @Test void failureEndpointRequiresTheServiceKeyAndReplaysTheOriginalReceipt() {
        Order order=saved("usr_buyer"); PaymentAdmissionInput input=input(order); begin.execute(input).orElseThrow();
        String url="http://127.0.0.1:"+port+"/internal/v1/orders/"+order.getOrderNo()+"/payment-admissions/"+input.admissionId()+"/failure";
        Map<String,Object> body=Map.of("paymentId",input.paymentId(),"supplierAttemptId","tx-http","failedAt",order.getCreatedAt().plusSeconds(1).toString());
        int unauthenticated=RestClient.create().post().uri(url).body(body).exchange((request,response)->response.getStatusCode().value());
        assertThat(unauthenticated).isIn(401,403);
        JsonNode first=RestClient.create().post().uri(url).header("X-Payment-Service-Key",KEY).body(body)
                .exchange((request,response)->{ assertThat(response.getStatusCode().value()).isEqualTo(200); return response.bodyTo(JsonNode.class); });
        when(clock.instant()).thenReturn(order.getExpiresAt());
        JsonNode replay=RestClient.create().post().uri(url).header("X-Payment-Service-Key",KEY).body(body)
                .exchange((request,response)->response.bodyTo(JsonNode.class));
        assertThat(replay).isEqualTo(first); assertThat(first.path("data").path("orderStatus").asText()).isEqualTo("PENDING");
    }

    @Test void theDurableExpiryScanClosesOnlyPendingOrdersAndCanRunAgainAfterRestart() {
        Order pending=saved("usr_buyer"); Order processing=saved("other-buyer"); begin.execute(input(processing)).orElseThrow();
        when(clock.instant()).thenReturn(processing.getExpiresAt().plusSeconds(1)); scan.execute(); scan.execute();
        assertThat(stored(pending).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(stored(processing).getStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(create.execute("usr_buyer",request()).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test void expiryAndItsActuallyFlushedOutboxRollBackTogether() {
        Order order=saved("usr_buyer"); when(clock.instant()).thenReturn(order.getExpiresAt());
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
            assertThat(expire.execute(order.getOrderNo())).isTrue(); outboxRows.flush();
            assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderExpired'")).isEqualTo(1);
            throw new IllegalStateException("rollback after real expiry Outbox flush");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(order).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderExpired'")).isZero();
        assertThat(expire.execute(order.getOrderNo())).isTrue();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderExpired'")).isEqualTo(1);
    }
    @Test void concurrentExpiryPublishesExactlyOnePurchaseBoundNotice() throws Exception {
        Order order=saved("usr_buyer"); when(clock.instant()).thenReturn(order.getExpiresAt());
        assertThat(together(()->expire.execute(order.getOrderNo()),()->expire.execute(order.getOrderNo()))).containsExactlyInAnyOrder(true,false);
        List<String> payloads=jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type='OrderExpired'",String.class);
        assertThat(payloads).hasSize(1); JsonNode body=mapper.readTree(payloads.getFirst());
        assertThat(body.path("requestEventId").asText()).isEqualTo(order.getRequestEventId());
        assertThat(body.path("amount").asLong()).isEqualTo(order.getAmount()); assertThat(body.path("expiresAt").asText()).isEqualTo(order.getExpiresAt().toString());
    }

    private boolean admitOrConflict(PaymentAdmissionInput input) {
        try { return begin.execute(input).isPresent(); }
        catch (BusinessException conflict) {
            assertThat(conflict.errorCode().statusCode()).isEqualTo(409);
            return false;
        }
    }
    private <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<T> one = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return first.call(); });
            Future<T> two = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
    }
    private Order saved(String buyer) {
        Order order = create.execute(buyer, request());
        when(clock.instant()).thenReturn(order.getCreatedAt().plusSeconds(1));
        return order;
    }
    private Order stored(Order order) { return orders.findById(order.getId()).orElseThrow(); }
    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private PaymentAdmissionInput input(Order order) { return input(order, id()); }
    private PaymentAdmissionInput input(Order order, String admissionId) {
        return new PaymentAdmissionInput(admissionId, order.getOrderNo(), order.getRequestEventId(), id(),
                order.getBuyerSubject(), order.getAmount(), order.getCurrency());
    }
    private PaymentResult paid(Order order, String paymentId, Instant at) {
        return new PaymentResult(id(), at, order.getOrderNo(), order.getRequestEventId(), paymentId,
                order.getBuyerSubject(), order.getAmount(), order.getCurrency(), OrderStatus.PAID, null, null);
    }
    private Map<String, Object> body(PaymentAdmissionInput input) {
        return Map.of("admissionId", input.admissionId(), "requestEventId", input.requestEventId(),
                "paymentId", input.paymentId(), "buyerSubject", input.buyerSubject(),
                "amount", input.amount(), "currency", input.currency());
    }
    private record Response(int status, JsonNode body, String cacheControl) {}
    private Response post(String orderNo, Object body, String key, String bearer) {
        RestClient.RequestBodySpec request = RestClient.create("http://127.0.0.1:" + port).post()
                .uri("/internal/v1/orders/" + orderNo + "/payment-admissions").contentType(MediaType.APPLICATION_JSON);
        if (key != null) request.header("X-Payment-Service-Key", key);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return request.body(body).exchange((outgoing, response) -> new Response(response.getStatusCode().value(),
                mapper.readTree(response.getBody()), response.getHeaders().getFirst("Cache-Control")));
    }
}
