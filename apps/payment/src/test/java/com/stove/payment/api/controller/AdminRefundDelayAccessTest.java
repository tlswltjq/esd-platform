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
import com.stove.payment.core.domain.RefundDelayQuery;
import com.stove.payment.core.domain.RefundInvestigationRequest;
import com.stove.payment.core.domain.RefundInvestigationView;
import com.stove.payment.core.service.ReadRefundDelays;
import com.stove.payment.core.service.RecordRefundInvestigation;
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

@WebMvcTest(AdminRefundDelayController.class)
@ContextConfiguration(classes = {AdminRefundDelayController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class AdminRefundDelayAccessTest {
    private static final String ROOT = "/api/v1/admin/refund-delays";
    private static final String ATTEMPT = "22222222-2222-2222-2222-222222222222";
    private static final String COMMAND = "33333333-3333-3333-3333-333333333333";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PortOneProperties properties;
    @MockitoBean ReadRefundDelays read;
    @MockitoBean RecordRefundInvestigation record;

    @BeforeEach void principal() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Jwt.withTokenValue(role).header("alg", "RS256").subject("usr_operator").claim("roles", List.of(role)).build();
        });
    }

    @Test void defaultListDisablesCaching() throws Exception {
        when(read.search(any(RefundDelayQuery.class))).thenReturn(Page.empty());
        mvc.perform(get(ROOT).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andExpect(jsonPath("$.data.totalElements").value(0));
        verify(read).search(new RefundDelayQuery(0, 20));
    }

    @Test void recordingTakesTheOperatorFromJwtAndReturnsAnAuditReceipt() throws Exception {
        Instant since = Instant.parse("2026-10-29T01:00:00Z");
        when(record.execute(any(RefundInvestigationRequest.class))).thenReturn(new RefundInvestigationView(COMMAND, ATTEMPT, "usr_operator", "확인 내용", since.plusSeconds(10), since));
        mvc.perform(post(ROOT + "/" + ATTEMPT + "/investigations").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + COMMAND + "\",\"note\":\"확인 내용\",\"operatorSubject\":\"forged\",\"recordedAt\":\"forged\"}"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.LOCATION, ROOT + "/" + ATTEMPT + "/investigations"))
                .andExpect(jsonPath("$.data.requestId").value(COMMAND)).andExpect(jsonPath("$.data.operatorSubject").value("usr_operator"))
                .andExpect(jsonPath("$.data.status").doesNotExist());
        verify(record).execute(new RefundInvestigationRequest(COMMAND, ATTEMPT, "usr_operator", "확인 내용"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "{", "{\"requestId\":\"bad\"}",
            "{\"requestId\":\"33333333-3333-3333-3333-333333333333\",\"note\":\" \"}"})
    void malformedCommandsNeverReachTheService(String body) throws Exception {
        mvc.perform(post(ROOT + "/" + ATTEMPT + "/investigations").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(record, read);
    }

    @Test void everyRouteRequiresAnAdministrator() throws Exception {
        for (String path : List.of(ROOT, ROOT + "/" + ATTEMPT, ROOT + "/" + ATTEMPT + "/investigations")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + role)).andExpect(status().isForbidden());
        }
        mvc.perform(post(ROOT + "/" + ATTEMPT + "/investigations")).andExpect(status().isUnauthorized());
        for (String role : List.of("MEMBER", "CREATOR", "REVIEWER"))
            mvc.perform(post(ROOT + "/" + ATTEMPT + "/investigations").header(HttpHeaders.AUTHORIZATION, "Bearer " + role)).andExpect(status().isForbidden());
        verifyNoInteractions(record, read);
    }

    @Test void invalidAttemptAndPaginationAreRejectedBeforeTheService() throws Exception {
        mvc.perform(get(ROOT + "/invalid").header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")).andExpect(status().isBadRequest());
        for (String query : List.of("page=-1", "size=0", "size=101", "page=wrong")) {
            mvc.perform(get(ROOT + "?" + query).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")).andExpect(status().isBadRequest());
            mvc.perform(get(ROOT + "/" + ATTEMPT + "/investigations?" + query).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(record, read);
    }
}
