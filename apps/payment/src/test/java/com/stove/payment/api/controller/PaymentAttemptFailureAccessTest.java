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
import com.stove.payment.core.domain.PaymentFailureHistory;
import com.stove.payment.core.domain.PaymentFailureHistoryQuery;
import com.stove.payment.core.service.ReadPaymentFailureHistory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentAttemptFailureController.class)
@ContextConfiguration(classes = {PaymentAttemptFailureController.class, SecurityConfig.class, CommonSecurityAutoConfiguration.class, GlobalExceptionHandler.class})
class PaymentAttemptFailureAccessTest {
    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String PATH = "/api/v1/payments/" + ORDER + "/attempt-failures";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PortOneProperties properties;
    @MockitoBean ReadPaymentFailureHistory read;

    @BeforeEach void principal() {
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue("token").header("alg", "RS256").subject("usr_buyer")
                .claim("roles", List.of(call.getArgument(0, String.class))).build());
    }
    @Test void theAuthenticatedBuyerIsUsedAndIncompleteHistoryDisablesCaching() throws Exception {
        when(read.execute(any(PaymentFailureHistoryQuery.class))).thenReturn(new PaymentFailureHistory(Page.empty(), null, true));
        mvc.perform(get(PATH + "?buyerSubject=forged").header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andExpect(jsonPath("$.data.collectionPending").value(true));
        verify(read).execute(new PaymentFailureHistoryQuery(ORDER, "usr_buyer", 0, 20));
    }
    @Test void paginationAndMalformedIdentifiersAreRejected() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101", "page=bad"))
            mvc.perform(get(PATH + "?" + query).header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/payments/bad/attempt-failures").header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER")).andExpect(status().isBadRequest());
        verifyNoInteractions(read);
    }
    @Test void historyRequiresMemberAndCannotBeWrittenThroughHttp() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String role : List.of("ADMIN", "CREATOR", "REVIEWER"))
            mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + role)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER")).andExpect(status().isForbidden());
        verifyNoInteractions(read);
    }
}
