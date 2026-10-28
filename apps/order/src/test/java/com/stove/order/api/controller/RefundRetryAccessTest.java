package com.stove.order.api.controller;

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
import com.stove.order.config.SecurityConfig;
import com.stove.order.core.domain.RefundRetryReceipt;
import com.stove.order.core.domain.RefundRetryRequest;
import com.stove.order.core.service.ReadRefund;
import com.stove.order.core.service.RequestRefund;
import com.stove.order.core.service.RequestRefundRetry;
import com.stove.order.core.service.RequestRefundReconsideration;
import com.stove.order.core.service.ReadRefundReviewRounds;
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

@WebMvcTest(RefundController.class)
@ContextConfiguration(classes = {RefundController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class RefundRetryAccessTest {
    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String ATTEMPT = "22222222-2222-2222-2222-222222222222";
    private static final String REFUND = "33333333-3333-3333-3333-333333333333";
    private static final String PATH = "/api/v1/orders/" + ORDER + "/refund";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RequestRefund request;
    @MockitoBean ReadRefund read;
    @MockitoBean RequestRefundRetry retry;
    @MockitoBean RequestRefundReconsideration reconsideration;
    @MockitoBean ReadRefundReviewRounds reviews;

    @BeforeEach void principal() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Jwt.withTokenValue(role).header("alg", "RS256").subject("usr_buyer").claim("roles", List.of(role)).build();
        });
    }

    @Test void explicitRetryUsesJwtSubjectAndReturnsOnlyTheStableCustomerReceipt() throws Exception {
        when(retry.execute(any(RefundRetryRequest.class))).thenReturn(new RefundRetryReceipt(REFUND, ATTEMPT,
                "internal-suspension-event", Instant.parse("2026-10-28T04:00:00Z")));
        mvc.perform(post(PATH + "/retries").header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"previousAttemptId\":\"" + ATTEMPT + "\",\"buyerSubject\":\"forged\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string(HttpHeaders.LOCATION, PATH))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.refundId").value(REFUND)).andExpect(jsonPath("$.data.previousAttemptId").value(ATTEMPT))
                .andExpect(jsonPath("$.data.receivedAt").value("2026-10-28T04:00:00Z"))
                .andExpect(jsonPath("$.data.suspensionRequestEventId").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist());
        verify(retry).execute(new RefundRetryRequest("usr_buyer", ORDER, ATTEMPT));
        verifyNoInteractions(request, read);
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "{", "{\"previousAttemptId\":null}", "{\"previousAttemptId\":\"\"}",
            "{\"previousAttemptId\":\"not-a-uuid\"}"})
    void missingOrMalformedAttemptCannotReachTheUseCase(String body) throws Exception {
        mvc.perform(post(PATH + "/retries").header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(retry, request, read);
    }

    @Test void anonymousAndEveryNonMemberRoleCannotRequestARetry() throws Exception {
        mvc.perform(post(PATH + "/retries")).andExpect(status().isUnauthorized());
        for (String role : new String[]{"ADMIN", "CREATOR", "REVIEWER"})
            mvc.perform(post(PATH + "/retries").header(HttpHeaders.AUTHORIZATION, "Bearer " + role)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"previousAttemptId\":\"" + ATTEMPT + "\"}"))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(retry, request, read);
    }

    @Test void malformedOrderNumberIsRejectedBeforeTheUseCase() throws Exception {
        mvc.perform(post("/api/v1/orders/not-a-uuid/refund/retries").header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"previousAttemptId\":\"" + ATTEMPT + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(retry, request, read);
    }
}
