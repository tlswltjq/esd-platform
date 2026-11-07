package com.stove.payment.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.security.CommonSecurityAutoConfiguration;
import com.stove.common.web.GlobalExceptionHandler;
import com.stove.common.web.error.BusinessException;
import com.stove.payment.config.PortOneProperties;
import com.stove.payment.config.SecurityConfig;
import com.stove.payment.core.domain.ApprovalClosureReceipt;
import com.stove.payment.core.domain.ApprovalClosureRequest;
import com.stove.payment.core.domain.ApprovalClosureView;
import com.stove.payment.core.domain.ApprovalObservation;
import com.stove.payment.core.domain.PaymentApprovalStatus;
import com.stove.payment.core.domain.PaymentErrorCode;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.service.CloseApprovalReview;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminApprovalClosureController.class, properties = "stove.payment.pg.provider=PORTONE_TEST")
@ContextConfiguration(classes = {AdminApprovalClosureController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class AdminApprovalClosureAccessTest {
    private static final String APPROVAL = "22222222-2222-2222-2222-222222222222";
    private static final String COMMAND = "33333333-3333-3333-3333-333333333333";
    private static final String PATH = "/api/v1/admin/approval-reviews/" + APPROVAL + "/closure";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PortOneProperties properties;
    @MockitoBean CloseApprovalReview close;

    @BeforeEach void principal() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Jwt.withTokenValue(role).header("alg", "RS256").subject("usr_operator").claim("roles", List.of(role)).build();
        });
    }

    @Test void theOperatorComesFromTheJwtAndTheReceiptCarriesNoSecret() throws Exception {
        Instant at = Instant.parse("2026-11-07T01:00:00Z");
        when(close.execute(any(ApprovalClosureRequest.class))).thenReturn(new ApprovalClosureReceipt(
                new ApprovalClosureView(COMMAND, APPROVAL, "usr_operator", "콘솔 취소 확인", ApprovalObservation.State.CANCELLED, at, at),
                "44444444-4444-4444-4444-444444444444", "payment-1", PaymentApprovalStatus.FAILED, PaymentStatus.FAILED));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + COMMAND + "\",\"note\":\"콘솔 취소 확인\",\"operatorSubject\":\"forged\",\"evidenceState\":\"READY\"}"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/admin/approval-reviews/" + APPROVAL))
                .andExpect(jsonPath("$.data.approvalStatus").value("FAILED")).andExpect(jsonPath("$.data.paymentStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.closure.operatorSubject").value("usr_operator"))
                .andExpect(jsonPath("$.data.closure.evidenceState").value("CANCELLED"))
                .andExpect(jsonPath("$.data.closure.protectedPayload").doesNotExist()).andExpect(jsonPath("$.data.protectedPayload").doesNotExist());
        verify(close).execute(new ApprovalClosureRequest(COMMAND, APPROVAL, "usr_operator", "콘솔 취소 확인"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "{", "{\"requestId\":\"bad\",\"note\":\"x\"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"note\":\" \"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\"}"})
    void malformedCommandsNeverReachTheClosure(String body) throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(close);
    }

    @Test void anOverlongReasonAndAnInvalidApprovalAreRejected() throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + COMMAND + "\",\"note\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/approval-reviews/invalid/closure").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + COMMAND + "\",\"note\":\"x\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(close);
    }

    @Test void onlyAnAdministratorCanClose() throws Exception {
        String body = "{\"requestId\":\"" + COMMAND + "\",\"note\":\"x\"}";
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + role).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(close);
    }

    @Test void aRefusedClosureIsAConflictWithItsBoundedReason() throws Exception {
        when(close.execute(any(ApprovalClosureRequest.class))).thenThrow(new BusinessException(PaymentErrorCode.CLOSURE_PG_PAID));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + COMMAND + "\",\"note\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CLOSURE_PG_PAID"));
    }
}
