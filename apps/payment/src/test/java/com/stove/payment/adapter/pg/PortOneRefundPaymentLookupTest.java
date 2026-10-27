package com.stove.payment.adapter.pg;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stove.payment.core.domain.PgRefundConfirmation;
import com.stove.payment.core.domain.PgRefundCancellationReceipt;
import com.stove.payment.core.domain.RefundLookupResult;
import com.stove.payment.core.domain.PgRefundLookupRequest;
import com.stove.payment.core.domain.PgUnavailable;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PortOneRefundPaymentLookupTest {
    private final PgRefundLookupRequest purchase = new PgRefundLookupRequest(id(), id(), 9000, "KRW", "pg-original",
            Instant.parse("2026-10-27T01:00:00Z"));
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.portone.io");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PortOneRefundPaymentLookup lookup = new PortOneRefundPaymentLookup(builder.build(), STORE, CHANNEL);

    @Test void aFullSuccessfulCancellationIsQueriedWithoutPostingOrClaimingAnAttemptIdentity() {
        expect(cancelled());
        PgRefundConfirmation result = lookup.find(purchase).orElseThrow();
        assertThat(result.purchase()).isEqualTo(purchase);
        assertThat(result.cancellations()).hasSize(1);
        assertThat(result.cancellations().get(0).getCancellationId()).isEqualTo("cancel-1");
        server.verify();
    }

    @Test void aFullyCancelledPaymentCanBeProvedByTheSumOfDistinctSuccessfulCancellations() {
        expect(cancelled().replace(cancellation("cancel-1", 9000), cancellation("cancel-2", 4000) + "," + cancellation("cancel-1", 5000)));
        assertThat(lookup.find(purchase).orElseThrow().cancellations()).hasSize(2);
    }

    @Test void anOptionalIndividualCancellationTimeIsNotInventedFromThePaymentTime() {
        expect(cancelled().replace(cancellation("cancel-1", 9000),
                "{\"status\":\"SUCCEEDED\",\"id\":\"cancel-1\",\"totalAmount\":9000}"));
        PgRefundConfirmation verified = lookup.find(purchase).orElseThrow();
        assertThat(verified.cancelledAt()).isEqualTo(Instant.parse("2026-10-27T01:02:00Z"));
        assertThat(verified.cancellations().get(0).getCancelledAt()).isNull();
    }

    @Test void requestedPartialAndOtherPaymentStatesDoNotBecomeCompletionOrFailure() {
        for (String status : List.of("PAID", "PARTIAL_CANCELLED", "READY", "FAILED", "UNKNOWN")) {
            server.reset(); expect(cancelled().replace("\"CANCELLED\"", "\"" + status + "\""));
            assertThat(lookup.find(purchase)).isEmpty();
        }
        server.reset(); expect(cancelled().replace("SUCCEEDED", "REQUESTED"));
        assertThatThrownBy(() -> lookup.find(purchase)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void mismatchedPurchaseAndIncompleteOrDuplicatedSuccessEvidenceAreRejected() {
        String valid = cancelled();
        for (String invalid : List.of(valid.replace(STORE, "store-other"), valid.replace(CHANNEL, "channel-other"),
                valid.replace("TEST", "LIVE"), valid.replace("pg-original", "different-transaction"),
                valid.replace(purchase.orderNo(), id()), valid.replace(purchase.paymentId(), id()), valid.replace("KRW", "USD"),
                valid.replace("TOSSPAY_V2", "TOSSPAYMENTS"), valid.replace("\"paid\":9000", "\"paid\":8000"),
                valid.replace("\"discount\":0", "\"discount\":1"), valid.replace("\"cancelled\":9000", "\"cancelled\":8000"),
                valid.replace("\"totalAmount\":9000", "\"totalAmount\":8999"), valid.replace("SUCCEEDED", "FAILED"),
                valid.replace(cancellation("cancel-1", 9000), cancellation("cancel-1", 4500) + "," + cancellation("cancel-1", 4500)),
                valid.replace("2026-10-27T01:00:00Z", "2026-10-26T01:00:00Z"))) {
            server.reset(); expect(invalid);
            assertThatThrownBy(() -> lookup.find(purchase)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void transportFailureRemainsUnavailableAndNeverCausesACancelRequest() {
        server.expect(requestTo(url())).andExpect(method(HttpMethod.GET)).andRespond(withServerError());
        assertThatThrownBy(() -> lookup.find(purchase)).isInstanceOf(PgUnavailable.class);
        server.verify();
    }

    @Test void onlyOurKnownFailedCancellationOnAWhollyUnrefundedPurchaseAllowsRestoration() {
        expect(unrefunded(failed()));
        RefundLookupResult result = lookup.inspect(purchase, receipt());
        assertThat(result.confirmation()).isNull(); assertThat(result.failure().failedReceipt()).isEqualTo(receipt().failed());
        assertThat(result.failure().snapshotHash()).matches("[a-f0-9]{64}"); server.verify();
    }

    @Test void pendingSuccessfulMissingOrSomeoneElsesCancellationCannotRestoreAccess() {
        for (String rows : List.of(failed().replace("FAILED", "REQUESTED"), failed().replace("FAILED", "SUCCEEDED"), "",
                failed().replace("cancel-own", "someone-else"), failed()+","+failed().replace("cancel-own", "another").replace("FAILED", "REQUESTED"))) {
            server.reset(); expect(unrefunded(rows));
            assertThat(lookup.inspect(purchase, receipt()).failure()).isNull();
        }
        server.reset(); expect(unrefunded(failed()).replace("\"cancelled\":0", "\"cancelled\":1000"));
        assertThat(lookup.inspect(purchase, receipt()).failure()).isNull();
    }

    @Test void unchangedMoneyDoesNotAllowDifferentPurchaseOrChangedReceiptEvidence() {
        for (String invalid : List.of(unrefunded(failed()).replace(STORE, "store-other"), unrefunded(failed()).replace("pg-original", "pg-other"),
                unrefunded(failed()).replace("\"totalAmount\":9000", "\"totalAmount\":8999"),
                unrefunded(failed()).replace("our reason", "another reason"), unrefunded(failed()+","+failed()))) {
            server.reset(); expect(invalid);
            assertThatThrownBy(() -> lookup.inspect(purchase, receipt())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private PgRefundCancellationReceipt receipt() { return new PgRefundCancellationReceipt(purchase, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "cancel-own", PgRefundCancellationReceipt.State.REQUESTED, Instant.parse("2026-10-27T01:01:00Z"), 0, 0, "our reason"); }
    private String failed() { return "{\"id\":\"cancel-own\",\"status\":\"FAILED\",\"totalAmount\":9000,\"taxFreeAmount\":0,\"vatAmount\":0,\"reason\":\"our reason\",\"requestedAt\":\"2026-10-27T01:01:00Z\"}"; }
    private String unrefunded(String rows) { return cancelled().replace("\"CANCELLED\"", "\"PAID\"")
            .replace("\"cancelled\":9000", "\"cancelled\":0").replace(cancellation("cancel-1", 9000), rows); }

    private void expect(String body) {
        server.expect(requestTo(url())).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    private String url() { return "https://api.portone.io/payments/" + purchase.paymentId() + "?storeId=" + STORE; }
    private String cancellation(String id, long amount) {
        return "{\"status\":\"SUCCEEDED\",\"id\":\"" + id + "\",\"totalAmount\":" + amount
                + ",\"cancelledAt\":\"2026-10-27T01:02:00Z\"}";
    }
    private String cancelled() {
        return """
                {"status":"CANCELLED","id":"%s","storeId":"%s","version":"V2","currency":"KRW",
                "channel":{"type":"TEST","key":"%s","pgProvider":"TOSSPAY_V2"},
                "amount":{"total":9000,"paid":9000,"discount":0,"cancelled":9000},
                "customData":"{\\"orderNo\\":\\"%s\\"}","paidAt":"2026-10-27T01:00:00Z","pgTxId":"pg-original",
                "cancelledAt":"2026-10-27T01:02:00Z","cancellations":[%s]}
                """.formatted(purchase.paymentId(), STORE, CHANNEL, purchase.orderNo(), cancellation("cancel-1", 9000));
    }
}
