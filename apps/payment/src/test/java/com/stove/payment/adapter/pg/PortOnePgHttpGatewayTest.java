package com.stove.payment.adapter.pg;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PortOnePgHttpGatewayTest {
    private final PgChargeRequest request = new PgChargeRequest(id(), id(), 9000, "KRW");
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.portone.io");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PortOnePgHttpGateway gateway = new PortOnePgHttpGateway(builder.build(), STORE, CHANNEL);

    @Test void paidIsQueriedAndBoundToTheLocalPurchaseWithoutInventingAPgFee() {
        server.expect(requestTo(url())).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(paid(), MediaType.APPLICATION_JSON));
        PgResult result = gateway.charge(request);
        assertThat(result.provider()).isEqualTo("PORTONE_TEST");
        assertThat(result.paymentId()).isEqualTo(request.paymentId());
        assertThat(result.orderNo()).isEqualTo(request.orderNo());
        assertThat(result.amount()).isEqualTo(9000);
        assertThat(result.occurredAt()).isEqualTo(Instant.parse("2026-10-27T01:02:03Z"));
        assertThat(result.transactionId()).isEqualTo("pg-approved-transaction");
        assertThat(result.decision()).isEqualTo(PgResult.Decision.APPROVED);
        assertThat(result.fee()).isNull();
        server.verify();
    }

    @Test void unknownOrUnavailableDoesNotCreateAChargeOrInventADecline() {
        server.expect(requestTo(url())).andExpect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(url())).andExpect(method(HttpMethod.GET)).andRespond(withServerError());
        assertThatThrownBy(() -> gateway.charge(request)).isInstanceOf(PgUnavailable.class);
        assertThatThrownBy(() -> gateway.charge(request)).isInstanceOf(PgUnavailable.class);
        server.verify();
    }

    @Test void nonPaidStatesRemainUnconfirmedInThisApprovalIncrement() {
        for (String status : List.of("READY", "PENDING", "FAILED", "CANCELLED", "PARTIAL_CANCELLED", "FUTURE")) {
            server.reset();
            server.expect(requestTo(url())).andRespond(withSuccess(paid().replace("\"PAID\"", "\"" + status + "\""), MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> gateway.charge(request)).isInstanceOf(PgUnavailable.class);
            server.verify();
        }
    }

    @Test void mismatchedStoreChannelTestModeAmountOrderAndCurrencyAreRejected() {
        for (String invalid : List.of(paid().replace(STORE, "store-other"), paid().replace(CHANNEL, "channel-other"),
                paid().replace("\"TEST\"", "\"LIVE\""), paid().replace("9000", "8999"),
                paid().replace(request.orderNo(), id()), paid().replace("KRW", "USD"),
                paid().replace(request.paymentId(), id()), paid().replace("TOSSPAY_V2", "TOSSPAYMENTS"))) {
            server.reset();
            server.expect(requestTo(url())).andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> gateway.charge(request)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String url() { return "https://api.portone.io/payments/" + request.paymentId() + "?storeId=" + STORE; }

    private String paid() {
        return """
                {"status":"PAID","id":"%s","storeId":"%s","version":"V2","currency":"KRW",
                 "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                 "amount":{"total":9000,"paid":9000,"discount":0,"cancelled":0},
                 "customData":"{\\"orderNo\\":\\"%s\\"}","paidAt":"2026-10-27T01:02:03Z","pgTxId":"pg-approved-transaction"}
                """.formatted(request.paymentId(), STORE, CHANNEL, request.orderNo());
    }
}
