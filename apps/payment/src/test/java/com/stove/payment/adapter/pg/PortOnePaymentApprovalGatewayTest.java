package com.stove.payment.adapter.pg;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PortOneFixtures.STORE;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.ApprovalPostRejected;
import com.stove.payment.core.domain.ApprovalPurchase;
import com.stove.payment.core.domain.ApprovalToken;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgUnavailable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PortOnePaymentApprovalGatewayTest {
    private final Instant at=Instant.parse("2026-10-29T01:00:00.123456Z");
    private final ApprovalPurchase purchase=new ApprovalPurchase(new PgChargeRequest(id(),id(),9000,"KRW"),"tx-original");
    private final RestClient.Builder builder=RestClient.builder().baseUrl("http://pg.example");
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private PortOnePaymentApprovalGateway gateway(Instant time) { return new PortOnePaymentApprovalGateway(builder.build(),STORE,CHANNEL,Clock.fixed(time,ZoneOffset.UTC)); }
    private String url() { return "http://pg.example/payments/"+purchase.purchase().paymentId(); }
    private String snapshot(String state,String tx) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("id",purchase.purchase().paymentId(),"transactionId",tx,"status",state,"storeId",STORE,
                "version","V2","currency","KRW","channel",Map.of("type","TEST","key",CHANNEL,"pgProvider","TOSSPAY_V2"),
                "amount",Map.of("total",9000,"paid",0,"discount",0,"cancelled",0),"customData",new ObjectMapper().writeValueAsString(Map.of("orderNo",purchase.purchase().orderNo()))));
    }
    private String paid() throws Exception {
        return snapshot("PAID","tx-original").replace("\"paid\":0","\"paid\":9000");
    }
    @Test void verifiedPaidPreservesTheOriginalPurchaseAndLeavesUnknownFeesNull() throws Exception {
        String body=paid();
        body=body.substring(0,body.length()-1)+",\"paidAt\":\""+at+"\",\"pgTxId\":\"pg-original\"}";
        server.expect(requestTo(url()+"?storeId="+STORE)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        ApprovalObservation observed=gateway(at).inspect(purchase);
        assertThat(observed.state()).isEqualTo(ApprovalObservation.State.PAID);
        assertThat(observed.paid().paymentId()).isEqualTo(purchase.purchase().paymentId());
        assertThat(observed.paid().amount()).isEqualTo(9000); assertThat(observed.paid().fee()).isNull();
        assertThat(observed.paid().transactionId()).isEqualTo("pg-original"); server.verify();
    }
    @Test void storeChannelLiveCurrencyProductAndTransactionMismatchesCannotBePaidEvidence() throws Exception {
        String body=paid();
        for(String invalid:List.of(body.replace(STORE,"other-store"),body.replace(CHANNEL,"other-channel"),body.replace("TEST","LIVE"),
                body.replace("KRW","USD"),body.replace(purchase.purchase().orderNo(),id()),body.replace(purchase.purchase().paymentId(),id()),
                body.replace("TOSSPAY_V2","TOSSPAYMENTS"),body.replace("tx-original","other-tx"))) {
            server.reset(); server.expect(requestTo(url()+"?storeId="+STORE)).andRespond(withSuccess(invalid,MediaType.APPLICATION_JSON));
            assertThatThrownBy(()->gateway(at).inspect(purchase)).isInstanceOf(IllegalArgumentException.class); server.verify();
        }
    }
    @Test void unavailableAndUnknownPurchasesRemainUnconfirmedWithoutSendingApproval() {
        server.expect(requestTo(url()+"?storeId="+STORE)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->gateway(at).inspect(purchase)).isInstanceOf(PgUnavailable.class); server.verify();
    }
    @Test void preparationFixesAllVerificationFieldsAndSubmitUsesTheQuotedIdempotencyKeyAndExactBytes() throws Exception {
        PortOnePaymentApprovalGateway gateway=gateway(at); String body=gateway.prepareBody(purchase,new ApprovalToken("private-token")); String command=id();
        assertThat(new ObjectMapper().readTree(body)).isEqualTo(new ObjectMapper().valueToTree(Map.of("storeId",STORE,"paymentToken","private-token","txId","tx-original","currency","KRW","totalAmount",9000,"isTest",true)));
        server.expect(requestTo(url()+"/confirm")).andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key","\""+command+"\""))
                .andExpect(content().string(body)).andRespond(withSuccess("{\"pgTxId\":\"summary-only\"}",MediaType.APPLICATION_JSON));
        gateway.submit(command,purchase,body,at,at.plusSeconds(60)); server.verify();
    }
    @Test void exactExpiryAndLostLeaseDoNotSendAnHttpRequest() {
        gateway(at.plusSeconds(10800)).submit(id(),purchase,"body",at,at.plusSeconds(10801));
        gateway(at.plusSeconds(60)).submit(id(),purchase,"body",at,at.plusSeconds(60)); server.verify();
    }
    @Test void amountOrTransactionMismatchIsRejectedBeforeAnyApproval() throws Exception {
        server.expect(requestTo(url()+"?storeId="+STORE)).andRespond(withSuccess(snapshot("READY","tx-original").replace("9000","8000"),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->gateway(at).inspect(purchase)).isInstanceOf(IllegalArgumentException.class); server.verify();
    }
    @Test void aDifferentCurrentTransactionRequiresVerifiedHistoryForTheExactNamedAttempt() throws Exception {
        server.expect(requestTo(url()+"?storeId="+STORE)).andRespond(withSuccess(snapshot("READY","tx-other"),MediaType.APPLICATION_JSON));
        server.expect(requestTo(url()+"/transactions?storeId="+STORE)).andRespond(withSuccess("{\"items\":[]}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->gateway(at).inspect(purchase)).isInstanceOf(PgUnavailable.class); server.verify();
    }
    @Test void readyIsOnlyAnObservationAndDoesNotInventAPaidResult() throws Exception {
        server.expect(requestTo(url()+"?storeId="+STORE)).andRespond(withSuccess(snapshot("READY","tx-original"),MediaType.APPLICATION_JSON));
        ApprovalObservation result=gateway(at).inspect(purchase); assertThat(result.state()).isEqualTo(ApprovalObservation.State.READY); assertThat(result.paid()).isNull(); server.verify();
    }
    @Test void anInformationMismatchIsAHardStopAndDoesNotEchoTheSupplierResponse() {
        server.expect(requestTo(url()+"/confirm")).andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"INFORMATION_MISMATCH\",\"message\":\"private-token\"}"));
        assertThatThrownBy(()->gateway(at).submit(id(),purchase,"private-token",at,at.plusSeconds(60)))
                .isInstanceOf(ApprovalPostRejected.class).hasMessageNotContaining("private-token").hasNoCause(); server.verify();
    }
    @Test void anOutstandingIdenticalRequestLeavesTheDecisionToLookup() {
        server.expect(requestTo(url()+"/confirm")).andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"IDEMPOTENCY_OUTSTANDING_REQUEST\"}"));
        gateway(at).submit(id(),purchase,"body",at,at.plusSeconds(60)); server.verify();
    }
}
