package com.stove.payment.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.GlobalExceptionHandler;
import com.stove.payment.api.application.PaymentCallbackFacade;
import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.api.controller.PaymentController;
import com.stove.payment.core.domain.PgApproval;
import com.stove.payment.core.service.PaymentService;
import com.stove.payment.infrastructure.pg.HmacPgCallbackVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PgCallbackAuthenticationFilterTest {
    private static final String SECRET = "test-pg-secret";
    private static final String BODY = "{\"result\":\"APPROVED\",\"orderNo\":\"ORD-1\","
            + "\"pgTxId\":\"PG-1\",\"paidAmount\":30000,"
            + "\"idempotencyKey\":\"IDEM-1\",\"method\":\"CARD\"}";

    private final PaymentService paymentService = mock(PaymentService.class);
    private final PaymentCallbackFacade callbackFacade = mock(PaymentCallbackFacade.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentController(
                    paymentService, mock(RefundFacade.class), callbackFacade))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new PgCallbackAuthenticationFilter(new HmacPgCallbackVerifier(
                    SECRET, new MockEnvironment())))
            .build();

    @Test
    void unsignedAndTamperedCallbacksNeverReachPayment() throws Exception {
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pg-Timestamp", timestamp)
                        .header("X-Pg-Signature", sign(timestamp, BODY))
                        .content(BODY.replace("30000", "1")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(paymentService, callbackFacade);
    }

    @Test
    void staleSignatureIsRejected() throws Exception {
        String timestamp = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pg-Timestamp", timestamp)
                        .header("X-Pg-Signature", sign(timestamp, BODY)).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(paymentService, callbackFacade);
    }

    @Test
    void validSignatureLetsTheOriginalBodyReachPayment() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pg-Timestamp", timestamp)
                        .header("X-Pg-Signature", sign(timestamp, BODY)).content(BODY))
                .andExpect(status().isOk());
        verify(callbackFacade).approve(any(PgApproval.class));
    }

    private static String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
