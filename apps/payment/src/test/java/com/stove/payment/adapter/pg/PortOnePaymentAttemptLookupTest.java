package com.stove.payment.adapter.pg;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgFailedPaymentAttempt;
import com.stove.payment.core.domain.PgUnavailable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PortOnePaymentAttemptLookupTest {
    private final PgChargeRequest purchase = new PgChargeRequest(id(), id(), 9000, "KRW");
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.portone.io");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PortOnePaymentAttemptLookup lookup = new PortOnePaymentAttemptLookup(builder.build(), STORE, CHANNEL);

    @Test void distinctSupplierAttemptIdsPreserveFailuresAndDeduplicateAnIdenticalAttempt() {
        String failed = failed("tx-first");
        expect("{\"items\":[" + failed + "," + failed("tx-second") + "," + failed + "]}");
        List<PgFailedPaymentAttempt> facts = lookup.findFailed(purchase);
        assertThat(facts).hasSize(2);
        assertThat(facts.getFirst()).isEqualTo(new PgFailedPaymentAttempt(purchase, "tx-first", Instant.parse("2026-10-29T01:00:00.123456Z"), Instant.parse("2026-10-29T01:00:01.123456Z")));
        assertThat(facts.get(1).supplierAttemptId()).isEqualTo("tx-second"); server.verify();
    }

    @Test void unpaidOrUnknownStatesNeverInventFailedAttempts() {
        for (String state : List.of("READY", "PENDING", "PAY_PENDING", "PAID", "CANCELLED", "FUTURE")) {
            server.reset(); expect("{\"items\":[" + failed("tx-1").replace("\"FAILED\"", "\"" + state + "\"") + "]}");
            assertThat(lookup.findFailed(purchase)).isEmpty(); server.verify();
        }
    }

    @Test void wrongPurchaseChannelAmountsAndTimesCannotBecomeFailureEvidence() {
        String valid = failed("tx-1");
        for (String wrong : List.of(valid.replace(STORE, "other-store"), valid.replace(CHANNEL, "other-channel"), valid.replace(purchase.orderNo(), id()),
                valid.replace(purchase.paymentId(), id()), valid.replace("9000", "8999"), valid.replace("\"TEST\"", "\"LIVE\""),
                valid.replace("\"paid\":0", "\"paid\":9000"), valid.replace("\"discount\":0", "\"discount\":100"), valid.replace("\"cancelled\":0", "\"cancelled\":9000"),
                valid.replace("KRW", "USD"), valid.replace("TOSSPAY_V2", "TOSSPAYMENTS"), valid.replace("01:00:01.123456789Z", "00:59:59Z"))) {
            server.reset(); expect("{\"items\":[" + wrong + "]}");
            assertThatThrownBy(() -> lookup.findFailed(purchase)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void unavailableMalformedOrConflictingHistoryIsNotAnEmptySuccessfulCollection() {
        for (HttpStatus status : List.of(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED, HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset(); server.expect(requestTo(url())).andRespond(withStatus(status));
            assertThatThrownBy(() -> lookup.findFailed(purchase)).isInstanceOf(PgUnavailable.class);
        }
        for (String body : List.of("{}", "{\"items\":[null]}", "{\"items\":[" + failed("tx-1") + "," + failed("tx-1").replace("01:00:01.123456789Z", "01:00:02Z") + "]}")) {
            server.reset(); expect(body); assertThatThrownBy(() -> lookup.findFailed(purchase)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String url() { return "https://api.portone.io/payments/" + purchase.paymentId() + "/transactions?storeId=" + STORE; }
    private void expect(String body) { server.expect(requestTo(url())).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)); }
    private String failed(String attempt) {
        return """
                {"status":"FAILED","id":"%s","paymentId":"%s","storeId":"%s","version":"V2","currency":"KRW",
                 "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                 "amount":{"total":9000,"paid":0,"discount":0,"cancelled":0},"customData":"{\\"orderNo\\":\\"%s\\"}",
                 "requestedAt":"2026-10-29T01:00:00.123456789Z","failedAt":"2026-10-29T01:00:01.123456789Z","failure":{}}
                """.formatted(attempt, purchase.paymentId(), STORE, CHANNEL, purchase.orderNo());
    }
}
