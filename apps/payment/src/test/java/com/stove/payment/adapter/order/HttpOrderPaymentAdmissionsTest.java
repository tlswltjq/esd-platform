package com.stove.payment.adapter.order;

import static com.stove.payment.PaymentFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stove.payment.core.domain.OrderAdmissionFailure;
import com.stove.payment.core.domain.OrderAdmissionRequest;
import com.stove.payment.core.domain.PgChargeRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpOrderPaymentAdmissionsTest {
    private final RestClient.Builder builder=RestClient.builder().baseUrl("http://order.example").defaultHeader("X-Payment-Service-Key","test-key");
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final HttpOrderPaymentAdmissions client=new HttpOrderPaymentAdmissions(builder.build());
    private final Instant at=Instant.parse("2026-10-29T01:00:00.123456Z");
    private final OrderAdmissionRequest request=new OrderAdmissionRequest(id(),id(),"usr_buyer",new PgChargeRequest(id(),id(),9000,"KRW"),at.plusSeconds(1800));
    private String root() { return "http://order.example/internal/v1/orders/"+request.purchase().orderNo()+"/payment-admissions"; }
    private String response() {
        return "{\"success\":true,\"data\":{\"admissionId\":\""+request.admissionId()+"\",\"orderNo\":\""+request.purchase().orderNo()
                +"\",\"paymentId\":\""+request.purchase().paymentId()+"\",\"admittedAt\":\""+at+"\",\"expiresAt\":\""+request.expiresAt()+"\"}}";
    }
    @Test void sendsTheOriginalPurchaseAndAcceptsOnlyAMatchingReceipt() {
        server.expect(requestTo(root())).andExpect(method(HttpMethod.POST)).andExpect(header("X-Payment-Service-Key","test-key"))
                .andExpect(content().json("{\"admissionId\":\""+request.admissionId()+"\",\"requestEventId\":\""+request.requestEventId()+"\",\"paymentId\":\""+request.purchase().paymentId()
                        +"\",\"buyerSubject\":\"usr_buyer\",\"amount\":9000,\"currency\":\"KRW\"}"))
                .andRespond(withSuccess(response(),MediaType.APPLICATION_JSON));
        assertThat(client.admit(request).orElseThrow().admittedAt()).isEqualTo(at); server.verify();
    }
    @Test void onlyTheExplicitExpiredCodeMeansThereWasNoAdmission() {
        server.expect(requestTo(root())).andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"error\":{\"code\":\"ORDER_EXPIRED\"}}"));
        assertThat(client.admit(request)).isEmpty(); server.verify();
    }
    @Test void anotherPurchasesReceiptCannotAuthorizeThisApproval() {
        server.expect(requestTo(root())).andRespond(withSuccess(response().replace(request.purchase().paymentId(),id()),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.admit(request)).isInstanceOf(IllegalArgumentException.class); server.verify();
    }
    @Test void failureUsesTheSameAdmissionAndValidatesTheClosureReceipt() {
        OrderAdmissionFailure failure=new OrderAdmissionFailure(request,"tx-test",at.plusSeconds(1));
        String path=root()+"/"+request.admissionId()+"/failure";
        server.expect(requestTo(path)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess(response().replace("\"admittedAt\":\""+at+"\"", "\"closedAt\":\""+at.plusSeconds(2)+"\"")
                .replace("\"expiresAt\":\""+request.expiresAt()+"\"", "\"orderStatus\":\"PENDING\""),MediaType.APPLICATION_JSON));
        assertThat(client.fail(failure).orderStatus()).isEqualTo("PENDING"); server.verify();
    }
}
