package com.stove.payment.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.security.CommonSecurityAutoConfiguration;
import com.stove.common.web.GlobalExceptionHandler;
import com.stove.payment.config.PortOneProperties;
import com.stove.payment.config.SecurityConfig;
import com.stove.payment.core.domain.RefundCancellationHoldQuery;
import com.stove.payment.core.domain.RefundCancellationResumeReceipt;
import com.stove.payment.core.domain.RefundCancellationResumeRequest;
import com.stove.payment.core.service.ReadRefundCancellationHolds;
import com.stove.payment.core.service.RequestRefundCancellationResume;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminRefundCancellationController.class)
@ContextConfiguration(classes = {AdminRefundCancellationController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class AdminRefundCancellationAccessTest {
    private static final String ROOT = "/api/v1/admin/refund-cancellation-holds";
    private static final String HOLD = "11111111-1111-1111-1111-111111111111";
    private static final String ATTEMPT = "22222222-2222-2222-2222-222222222222";
    private static final String COMMAND = "33333333-3333-3333-3333-333333333333";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PortOneProperties properties;
    @MockitoBean ReadRefundCancellationHolds read;
    @MockitoBean RequestRefundCancellationResume resume;

    @BeforeEach void principal() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Jwt.withTokenValue(role).header("alg", "RS256").subject("usr_operator").claim("roles", List.of(role)).build();
        });
    }

    @Test void defaultListIsCurrentOnlyAndDisablesCaching() throws Exception {
        when(read.search(any(RefundCancellationHoldQuery.class))).thenReturn(Page.empty());
        mvc.perform(get(ROOT).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andExpect(jsonPath("$.data.totalElements").value(0));
        verify(read).search(new RefundCancellationHoldQuery(true, 0, 20));
    }

    @Test void resumeTakesTheOperatorFromJwtAndReturnsAnInstructionReceipt() throws Exception {
        when(resume.execute(any(RefundCancellationResumeRequest.class))).thenReturn(new RefundCancellationResumeReceipt(COMMAND, ATTEMPT, HOLD,
                Instant.parse("2026-10-28T13:00:00Z")));
        mvc.perform(post(ROOT + "/" + HOLD + "/resume").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + COMMAND + "\",\"attemptId\":\"" + ATTEMPT + "\",\"note\":\"권한 설정 확인\",\"operatorSubject\":\"forged\"}"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.LOCATION, ROOT + "/" + HOLD))
                .andExpect(jsonPath("$.data.requestId").value(COMMAND)).andExpect(jsonPath("$.data.receivedAt").value("2026-10-28T13:00:00Z"))
                .andExpect(jsonPath("$.data.status").doesNotExist());
        verify(resume).execute(new RefundCancellationResumeRequest(COMMAND, ATTEMPT, HOLD, "usr_operator", "권한 설정 확인"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "{", "{\"requestId\":\"bad\"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"attemptId\":\"22222222-2222-2222-2222-222222222222\",\"note\":\" \"}"})
    void malformedCommandsNeverReachTheService(String body) throws Exception {
        mvc.perform(post(ROOT + "/" + HOLD + "/resume").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(resume, read);
    }

    @Test void everyRouteRequiresAnAdministrator() throws Exception {
        for (String role : new String[]{"MEMBER", "CREATOR", "REVIEWER"}) {
            mvc.perform(get(ROOT).header(HttpHeaders.AUTHORIZATION, "Bearer " + role)).andExpect(status().isForbidden());
            mvc.perform(get(ROOT + "/" + HOLD).header(HttpHeaders.AUTHORIZATION, "Bearer " + role)).andExpect(status().isForbidden());
            mvc.perform(post(ROOT + "/" + HOLD + "/resume").header(HttpHeaders.AUTHORIZATION, "Bearer " + role)).andExpect(status().isForbidden());
        }
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/" + HOLD)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT + "/" + HOLD + "/resume")).andExpect(status().isUnauthorized());
        verifyNoInteractions(resume, read);
    }

    @Test void invalidHoldAndPaginationAreRejectedBeforeTheService() throws Exception {
        mvc.perform(get(ROOT + "/invalid").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")).andExpect(status().isBadRequest());
        for (String query : new String[]{"page=-1", "size=0", "size=101", "activeOnly=invalid"})
            mvc.perform(get(ROOT + "?" + query).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")).andExpect(status().isBadRequest());
        verifyNoInteractions(resume, read);
    }

}
