package com.stove.order.api.controller;

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
import com.stove.order.config.SecurityConfig;
import com.stove.order.core.domain.RefundStatus;
import com.stove.order.core.domain.RefundView;
import com.stove.order.core.service.ReadRefund;
import com.stove.order.core.service.RequestRefund;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RefundController.class)
@ContextConfiguration(classes = {RefundController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class})
class RefundAccessTest {
    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String PATH = "/api/v1/orders/" + ORDER + "/refund";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RequestRefund request;
    @MockitoBean ReadRefund read;

    @BeforeEach void principal() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String bearer = call.getArgument(0, String.class);
            return Jwt.withTokenValue(bearer).header("alg", "RS256").subject("usr_buyer")
                    .claim("roles", List.of(bearer.equals("member") ? "MEMBER" : "ADMIN")).build();
        });
    }

    @Test void requestUsesTheAuthenticatedBuyerAndReturnsAnAcceptedReceiptAndLocation() throws Exception {
        when(read.execute(ORDER, "usr_buyer")).thenReturn(view(RefundStatus.PENDING_PAYMENT_REFUND));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member"))
                .andExpect(status().isAccepted()).andExpect(header().string(HttpHeaders.LOCATION, PATH))
                .andExpect(jsonPath("$.data.status").value("PROCESSING")).andExpect(jsonPath("$.data.amount").value(9000));
        verify(request).execute("usr_buyer", ORDER); verify(read).execute(ORDER, "usr_buyer");
    }

    @Test void anonymousAndNonMemberCannotRequestOrReadRefunds() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized()); mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer admin")).andExpect(status().isForbidden());
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer admin")).andExpect(status().isForbidden());
        verifyNoInteractions(request, read);
    }

    @ParameterizedTest @EnumSource(RefundStatus.class)
    void customerStatusesPreserveTerminalFactsAndHideInternalProcessingStages(RefundStatus state) throws Exception {
        when(read.execute(ORDER, "usr_buyer")).thenReturn(view(state));
        String expected = switch (state) { case PENDING_REVIEW, COMPLETED, FAILED, REJECTED -> state.name(); default -> "PROCESSING"; };
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(expected));
        verifyNoInteractions(request);
    }

    @ParameterizedTest @EnumSource(value = RefundStatus.class, names = {"COMPLETED", "FAILED", "REJECTED"})
    void repeatingARequestForATerminalRefundReturnsItsCurrentResult(RefundStatus state) throws Exception {
        when(read.execute(ORDER, "usr_buyer")).thenReturn(view(state));
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(state.name()));
    }

    private RefundView view(RefundStatus state) {
        return new RefundView("refund-fixture", ORDER, 9000, "KRW", state, Instant.parse("2026-10-28T02:00:00Z"),
                state == RefundStatus.COMPLETED ? Instant.parse("2026-10-28T02:01:00Z") : null,
                state == RefundStatus.REJECTED ? "환불 심사 반려 사유" : null);
    }
}
