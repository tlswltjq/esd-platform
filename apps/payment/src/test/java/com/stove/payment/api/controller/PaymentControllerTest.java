package com.stove.payment.api.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.GlobalExceptionHandler;
import com.stove.payment.api.application.RefundFacade;
import com.stove.payment.api.controller.dto.PgCallbackRequest;
import com.stove.payment.api.controller.dto.PreparePaymentRequest;
import com.stove.payment.core.domain.PgDecline;
import com.stove.payment.core.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PG 콜백의 입구 검증. <b>돈이 시스템에 들어오는 유일한 문</b>이다.
 *
 * <p>콜백은 외부(PG)가 보내는 요청이라 우리가 형태를 통제할 수 없다.
 * 검증이 헐거우면 금액이 비었거나 멱등키가 없는 승인이 그대로 들어와
 * 결제 상태를 바꿔 버린다 — D-008 은 멱등키가 재사용됐을 때 다른 주문의 승인을
 * 삼킨 결함이었고, 그 전제는 "멱등키가 반드시 있다"는 것이다.
 */
class PaymentControllerTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final PaymentService paymentService = mock(PaymentService.class);
    private final RefundFacade refundFacade = mock(RefundFacade.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PaymentController(paymentService, refundFacade))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private String callback(String orderNo, String pgTxId, Long paidAmount, String idempotencyKey)
            throws Exception {
        return objectMapper.writeValueAsString(
                new PgCallbackRequest.Approved(orderNo, pgTxId, paidAmount, idempotencyKey, "CARD"));
    }

    private ResultActions postCallback(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/payments/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("멱등키 없는 콜백은 400 이다 — 중복 승인을 걸러낼 수단이 없다")
    void callbackWithoutIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback("ORD-1", "PG-1", 30_000L, "")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("금액 없는 콜백은 400 이다")
    void callbackWithoutAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback("ORD-1", "PG-1", null, "IDEM-1")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("0원·음수 승인은 400 이다 — @Positive 가 지키는 경계")
    void nonPositiveAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback("ORD-1", "PG-1", 0L, "IDEM-1")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback("ORD-1", "PG-1", -1L, "IDEM-1")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("주문번호·PG 거래번호가 비면 400 이다")
    void blankIdentifiersAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback("", "PG-1", 30_000L, "IDEM-1")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callback("ORD-1", "", 30_000L, "IDEM-1")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("result 가 없는 콜백은 400 이다 — 기본값을 승인으로 두지 않는다")
    void callbackWithoutResultIsRejected() throws Exception {
        postCallback("""
                {"orderNo":"ORD-1","pgTxId":"PG-1","paidAmount":30000,"idempotencyKey":"IDEM-1"}
                """)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("모르는 result 는 400 이다 — 판별할 수 없는 결과를 추측하지 않는다")
    void callbackWithUnknownResultIsRejected() throws Exception {
        postCallback("""
                {"result":"MAYBE","orderNo":"ORD-1","pgTxId":"PG-1"}
                """)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("거절 콜백은 거절 경로로 간다")
    void declinedCallbackGoesToDeclineHandler() throws Exception {
        postCallback("""
                {"result":"DECLINED","orderNo":"ORD-1","pgTxId":"PG-1",
                 "reasonCode":"REJECT_CARD_COMPANY","reason":"카드사 거절"}
                """)
                .andExpect(status().isOk());

        verify(paymentService).handleDecline(
                new PgDecline("ORD-1", "PG-1", "REJECT_CARD_COMPANY", "카드사 거절"));
    }

    @Test
    @DisplayName("사유 코드 없는 거절은 400 이다 — 집계할 수 없는 실패는 운영에서 쓸 수 없다")
    void declineWithoutReasonCodeIsRejected() throws Exception {
        postCallback("""
                {"result":"DECLINED","orderNo":"ORD-1","pgTxId":"PG-1","reason":"카드사 거절"}
                """)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("거절 변형을 더해도 승인 쪽 금액 검증은 그대로다")
    void approvalValidationSurvivesTheSplit() throws Exception {
        postCallback("""
                {"result":"APPROVED","orderNo":"ORD-1","pgTxId":"PG-1",
                 "paidAmount":0,"idempotencyKey":"IDEM-1"}
                """)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("결제 수단 없는 준비 요청은 400 이다")
    void blankMethodIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/payments/ORD-1/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PreparePaymentRequest(""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }
}
