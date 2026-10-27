package com.stove.payment.adapter.pg;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.domain.RefundCancellationDispatch;
import com.stove.payment.core.domain.RefundCancellationPermission;
import com.stove.payment.core.domain.RefundExecutionRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class PortOneRefundCancellationGatewayTest {
    private static final Instant FIRST = Instant.parse("2026-10-27T01:00:00Z");
    private final RefundExecutionRequest request = new RefundExecutionRequest(id(), FIRST, id(), id(), id(), id(), "buyer", 9000, "KRW", id());
    private final RefundCancellationPermission permission = new RefundCancellationPermission(request, FIRST, false);
    private final PgRefundLookupRequest purchase = new PgRefundLookupRequest(request.paymentId(), request.orderNo(), 9000, "KRW", "pg-original", FIRST.minusSeconds(60));
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.portone.io");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient client = builder.build();
    private final PortOneRefundCancellationGateway gateway = gateway(Clock.fixed(FIRST.plusSeconds(1), ZoneOffset.UTC));

    @Test void fullAmountAndExpectedBalanceAreBothFrozenToTheOriginalAmount() throws Exception {
        String body = gateway.prepareBody(request);
        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.path("amount").longValue()).isEqualTo(9000);
        assertThat(json.path("currentCancellableAmount").longValue()).isEqualTo(9000);
        assertThat(json.path("storeId").textValue()).isEqualTo(STORE);
        assertThat(json.path("reason").textValue()).contains(request.refundId());
        assertThat(body).isEqualTo(gateway.prepareBody(request));
    }

    @Test void aClearUntouchedPurchasePostsTheExactBodyAndQuotedAttemptKey() {
        String body = body(); expectRead(paid());
        expectPost(body, withSuccess("{\"cancellation\":{\"status\":\"REQUESTED\"}}", MediaType.APPLICATION_JSON));
        assertThat(gateway.send(permission, purchase, body)).isEqualTo(RefundCancellationDispatch.UNCONFIRMED);
        server.verify();
    }

    @Test void aValidatedSucceededResponseCarriesOurAttemptAndOriginalPurchaseWithoutASecondGet() {
        expectRead(paid()); expectPost(body(), withSuccess(success(), MediaType.APPLICATION_JSON));
        RefundCancellationDispatch result = gateway.send(permission, purchase, body());
        assertThat(result.success()).isNotNull();
        assertThat(result.success().attemptId()).isEqualTo(request.attemptId());
        assertThat(result.success().purchase()).isEqualTo(purchase);
        assertThat(result.success().cancellation().getCancellationId()).isEqualTo("cancel-1");
        assertThat(result.success().cancellation().getCancelledAt()).isNull();
        server.verify();
    }

    @Test void requestedAndFailedResponsesCarryCorrelationButDoNotClaimSuccessOrRestoration() {
        for (String state : List.of("REQUESTED", "FAILED")) {
            server.reset(); expectRead(paid()); expectPost(body(), withSuccess(success().replace("SUCCEEDED", state), MediaType.APPLICATION_JSON));
            RefundCancellationDispatch result = gateway.send(permission, purchase, body());
            assertThat(result.success()).isNull(); assertThat(result.receipt().state().name()).isEqualTo(state);
            assertThat(result.receipt().cancellationId()).isEqualTo("cancel-1");
            assertThat(result.receipt().attemptId()).isEqualTo(request.attemptId()); server.verify();
        }
    }

    @Test void invalidSuccessEvidenceAndOtherResponseStatesRemainUnconfirmed() {
        for (String response : List.of(success().replace("9000", "8999"), success().replace("cancel-1", " "),
                success().replace("ESD refund", "different reason"), success().replace("\"vatAmount\":818,", ""),
                success().replace("2026-10-27T01:00:01Z", "not-a-time"), "{malformed", "{}")) {
            server.reset(); expectRead(paid()); expectPost(body(), withSuccess(response, MediaType.APPLICATION_JSON));
            assertThat(gateway.send(permission, purchase, body())).isEqualTo(RefundCancellationDispatch.UNCONFIRMED);
            server.verify();
        }
    }

    private String success() {
        return "{\"cancellation\":{\"status\":\"SUCCEEDED\",\"id\":\"cancel-1\",\"totalAmount\":9000,\"taxFreeAmount\":0,\"vatAmount\":818,"
                + "\"reason\":\"ESD refund " + request.refundId() + "\",\"requestedAt\":\"2026-10-27T01:00:01Z\"}}";
    }

    @Test void anExistingPartialCancellationDoesNotTriggerARequestForTheRemainder() {
        expectRead(paid().replace("\"status\":\"PAID\"", "\"status\":\"PARTIAL_CANCELLED\"")
                .replace("\"cancelled\":0", "\"cancelled\":3000"));
        assertThat(gateway.send(permission, purchase, body())).isEqualTo(RefundCancellationDispatch.BALANCE_CHANGED);
        server.verify();
    }

    @Test void aBalanceChangeBetweenReadAndPostIsAnObservationRatherThanAFinalFailure() {
        expectRead(paid());
        expectPost(body(), withStatus(HttpStatus.CONFLICT).body("{\"type\":\"CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN\"}")
                .contentType(MediaType.APPLICATION_JSON));
        assertThat(gateway.send(permission, purchase, body())).isEqualTo(RefundCancellationDispatch.BALANCE_CHANGED);
        server.verify();
    }

    @Test void inFlightAndAlreadyCancelledPaymentsAreNotPostedAgain() {
        for (String response : List.of(paid().replace("PAID", "CANCELLED").replace("\"cancelled\":0", "\"cancelled\":9000"),
                paid().replace("\"cancellations\":[]", "\"cancellations\":[{\"status\":\"REQUESTED\"}]"))) {
            server.reset(); expectRead(response);
            assertThat(gateway.send(permission, purchase, body())).isEqualTo(RefundCancellationDispatch.NOT_READY);
            server.verify();
        }
    }

    @Test void changedOrUnsafeBodiesAndWrongPurchaseEvidenceNeverReachACancelPost() {
        for (String invalid : List.of(body().replace("9000", "6000"), body().replace(STORE, "store-other"),
                body().replace("\"amount\":9000,", ""), body().replace("}", ",\"skipWebhook\":true}"))) {
            server.reset();
            assertThatThrownBy(() -> gateway.send(permission, purchase, invalid)).isInstanceOf(IllegalArgumentException.class);
            server.verify();
        }
        for (String invalid : List.of(paid().replace(CHANNEL, "other-channel"), paid().replace("pg-original", "other-pg-tx"),
                paid().replace("TEST", "LIVE"), paid().replace(request.orderNo(), id()))) {
            server.reset(); expectRead(invalid);
            assertThatThrownBy(() -> gateway.send(permission, purchase, body())).isInstanceOf(IllegalArgumentException.class);
            server.verify();
        }
    }

    @Test void anExpiredPermissionCannotStartOrFinishAnHttpPreflight() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(FIRST.plusSeconds(10800));
        assertThatThrownBy(() -> gateway(clock).send(permission, purchase, body())).isInstanceOf(BusinessException.class);
        server.verify();
        when(clock.instant()).thenReturn(FIRST.plusSeconds(10799), FIRST.plusSeconds(10800));
        expectRead(paid());
        assertThatThrownBy(() -> gateway(clock).send(permission, purchase, body())).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test void anOutstandingKeyOrLostResponseDoesNotBecomeABalanceMismatchOrFinalFailure() {
        expectRead(paid());
        expectPost(body(), withStatus(HttpStatus.CONFLICT).body("{\"type\":\"IDEMPOTENCY_OUTSTANDING_REQUEST\"}")
                .contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.send(permission, purchase, body())).isInstanceOf(PgUnavailable.class);
        server.verify();
    }

    private PortOneRefundCancellationGateway gateway(Clock clock) { return new PortOneRefundCancellationGateway(client, STORE, CHANNEL, clock); }
    private String body() { return "{\"storeId\":\"" + STORE + "\",\"amount\":9000,\"currentCancellableAmount\":9000,\"reason\":\"ESD refund " + request.refundId() + "\"}"; }
    private String url() { return "https://api.portone.io/payments/" + request.paymentId(); }
    private void expectRead(String response) { server.expect(requestTo(url() + "?storeId=" + STORE)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON)); }
    private void expectPost(String body, ResponseCreator response) {
        server.expect(requestTo(url() + "/cancel")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "\"" + request.attemptId() + "\""))
                .andExpect(content().string(body)).andRespond(response);
    }
    private String paid() {
        return """
                {"status":"PAID","id":"%s","storeId":"%s","version":"V2","currency":"KRW",
                "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                "amount":{"total":9000,"paid":9000,"discount":0,"cancelled":0},"cancellations":[],
                "customData":"{\\"orderNo\\":\\"%s\\"}","paidAt":"%s","pgTxId":"pg-original"}
                """.formatted(request.paymentId(), STORE, CHANNEL, request.orderNo(), purchase.paidAt());
    }
}
