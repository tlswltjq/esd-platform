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
import com.stove.payment.core.domain.PaymentErrorCode;
import com.stove.payment.core.domain.RefundAttribution;
import com.stove.payment.core.domain.RefundClosure;
import com.stove.payment.core.domain.RefundClosureReceipt;
import com.stove.payment.core.domain.RefundClosureRequest;
import com.stove.payment.core.domain.RefundClosureView;
import com.stove.payment.core.domain.RefundExecutionStatus;
import com.stove.payment.core.service.CloseRefundDelay;
import com.stove.payment.core.service.CloseRefundFailure;
import com.stove.payment.core.domain.RefundFailureClosure;
import com.stove.payment.core.domain.RefundFailureClosureReceipt;
import com.stove.payment.core.domain.RefundFailureClosureRequest;
import com.stove.payment.core.domain.RefundFailureClosureView;
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

@WebMvcTest(controllers = AdminRefundDelayClosureController.class, properties = "stove.payment.pg.provider=PORTONE_TEST")
@ContextConfiguration(classes = {AdminRefundDelayClosureController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class AdminRefundDelayClosureAccessTest {
    private static final String ATTEMPT = "22222222-2222-2222-2222-222222222222";
    private static final String COMMAND = "33333333-3333-3333-3333-333333333333";
    private static final String PATH = "/api/v1/admin/refund-delays/" + ATTEMPT + "/closure";
    private static final String BODY = "{\"requestId\":\"" + COMMAND + "\",\"attribution\":\"EXTERNAL_CANCELLATION\",\"note\":\"콘솔 취소 확인\"}";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PortOneProperties properties;
    @MockitoBean CloseRefundDelay close;
    @MockitoBean CloseRefundFailure fail;

    @BeforeEach void principal() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Jwt.withTokenValue(role).header("alg", "RS256").subject("usr_operator").claim("roles", List.of(role)).build();
        });
    }

    @Test void theOperatorComesFromTheJwtAndTheReceiptCarriesTheJudgment() throws Exception {
        Instant at = Instant.parse("2026-11-08T01:00:00Z");
        when(close.execute(any(RefundClosureRequest.class))).thenReturn(new RefundClosureReceipt(
                new RefundClosureView(COMMAND, ATTEMPT, "usr_operator", RefundAttribution.EXTERNAL_CANCELLATION, "콘솔 취소 확인",
                        RefundClosure.Outcome.COMPLETED, at.minusSeconds(60), at, at),
                "44444444-4444-4444-4444-444444444444", "55555555-5555-5555-5555-555555555555", "payment-1", RefundExecutionStatus.PENDING,
                at, "PG_QUERY_UNATTRIBUTED"));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + COMMAND + "\",\"attribution\":\"EXTERNAL_CANCELLATION\",\"note\":\"콘솔 취소 확인\","
                                + "\"operatorSubject\":\"forged\",\"outcome\":\"RECORDED\"}"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/admin/refund-delays/" + ATTEMPT))
                .andExpect(jsonPath("$.data.attemptId").value(ATTEMPT)).andExpect(jsonPath("$.data.executionStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.refundConfirmationSource").value("PG_QUERY_UNATTRIBUTED"))
                .andExpect(jsonPath("$.data.closure.operatorSubject").value("usr_operator"))
                .andExpect(jsonPath("$.data.closure.attribution").value("EXTERNAL_CANCELLATION"))
                .andExpect(jsonPath("$.data.closure.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.data.cancellationRequestBody").doesNotExist());
        verify(close).execute(new RefundClosureRequest(COMMAND, ATTEMPT, "usr_operator", RefundAttribution.EXTERNAL_CANCELLATION, "콘솔 취소 확인"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "{",
            "{\"requestId\":\"bad\",\"attribution\":\"OUR_REQUEST\",\"note\":\"x\"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"attribution\":\"OUR_REQUEST\",\"note\":\" \"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"note\":\"x\"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"attribution\":\"our_request\",\"note\":\"x\"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"attribution\":\"CONSOLE\",\"note\":\"x\"}"})
    void malformedCommandsNeverReachTheClosure(String body) throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(close);
    }

    @Test void anOverlongNoteAndAnInvalidAttemptAreRejected() throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + COMMAND + "\",\"attribution\":\"UNDETERMINED\",\"note\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/refund-delays/invalid/closure").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(close);
    }

    @Test void onlyAnAdministratorCanClose() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + role).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(close);
    }

    @Test void aPaidPurchaseIsAConflictThatAsksForTheConsoleFirst() throws Exception {
        when(close.execute(any(RefundClosureRequest.class))).thenThrow(new BusinessException(PaymentErrorCode.REFUND_CLOSURE_PG_NOT_CANCELLED));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REFUND_CLOSURE_PG_NOT_CANCELLED"));
    }

    private static final String FAILURE_PATH = "/api/v1/admin/refund-delays/" + ATTEMPT + "/failure-closure";
    private static final String FAILURE_BODY = "{\"requestId\":\"" + COMMAND + "\",\"note\":\"403 원인 확인\"}";

    /** Decision 128: the operator comes from the JWT; forged fields in the body are ignored. */
    @Test void theFailureClosureOperatorComesFromTheJwtAndTheReceiptCarriesTheEvidence() throws Exception {
        Instant at = Instant.parse("2026-11-08T04:00:00Z");
        when(fail.execute(any(RefundFailureClosureRequest.class))).thenReturn(new RefundFailureClosureReceipt(
                new RefundFailureClosureView(COMMAND, ATTEMPT, "usr_operator", "403 원인 확인", RefundFailureClosure.Evidence.NO_LINKED_CANCELLATION,
                        RefundFailureClosure.StopReason.CANCELLATION_HOLD, List.of(), "c".repeat(64), at.minusSeconds(1), at),
                "44444444-4444-4444-4444-444444444444", "55555555-5555-5555-5555-555555555555", "payment-1", RefundExecutionStatus.FAILED, at));
        mvc.perform(post(FAILURE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + COMMAND + "\",\"note\":\"403 원인 확인\",\"operatorSubject\":\"forged\",\"executionStatus\":\"PENDING\"}"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/admin/refund-delays/" + ATTEMPT))
                .andExpect(jsonPath("$.data.executionStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.failureClosure.operatorSubject").value("usr_operator"))
                .andExpect(jsonPath("$.data.failureClosure.evidence").value("NO_LINKED_CANCELLATION"))
                .andExpect(jsonPath("$.data.failureClosure.stopReason").value("CANCELLATION_HOLD"));
        verify(fail).execute(new RefundFailureClosureRequest(COMMAND, ATTEMPT, "usr_operator", "403 원인 확인"));
        verifyNoInteractions(close);
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "{\"requestId\":\"bad\",\"note\":\"x\"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"note\":\" \"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\"}"})
    void malformedFailureClosuresNeverReachTheService(String body) throws Exception {
        mvc.perform(post(FAILURE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(fail);
    }

    @Test void onlyAnAdministratorCanCloseAsFailedAndAMovedPaymentIsAConflict() throws Exception {
        mvc.perform(post(FAILURE_PATH).contentType(MediaType.APPLICATION_JSON).content(FAILURE_BODY)).andExpect(status().isUnauthorized());
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            mvc.perform(post(FAILURE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + role).contentType(MediaType.APPLICATION_JSON).content(FAILURE_BODY))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(fail);
        when(fail.execute(any(RefundFailureClosureRequest.class))).thenThrow(new BusinessException(PaymentErrorCode.REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED));
        mvc.perform(post(FAILURE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON).content(FAILURE_BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED"));
    }
}
