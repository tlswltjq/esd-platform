package com.stove.settlement.api.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.kafka.ops.DltOpsController;
import com.stove.common.kafka.ops.DltOpsService;
import com.stove.common.messaging.ops.OutboxOpsController;
import com.stove.common.messaging.ops.OutboxOpsService;
import com.stove.common.security.CommonSecurityAutoConfiguration;
import com.stove.settlement.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@WebMvcTest({DltOpsController.class, OutboxOpsController.class})
@ContextConfiguration(classes = {SecurityConfig.class, CommonSecurityAutoConfiguration.class,
        DltOpsController.class, OutboxOpsController.class})
class RecoveryOpsAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean DltOpsService dlt;
    @MockitoBean OutboxOpsService outbox;

    @BeforeEach
    void principals() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String role = call.getArgument(0, String.class);
            return Jwt.withTokenValue(role).header("alg", "RS256").subject("operator")
                    .claim("roles", List.of(role)).build();
        });
        when(dlt.peek(anyString(), anyInt())).thenReturn(List.of());
        when(outbox.deadEvents()).thenReturn(List.of());
        when(outbox.requeue(anyString())).thenReturn(true);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/ops/dlt", "POST,/api/v1/ops/dlt/replay",
            "GET,/api/v1/ops/outbox/dead", "POST,/api/v1/ops/outbox/dead/event-1/requeue"})
    void adminCanUseExplicitRecoveryOperations(HttpMethod method, String path) throws Exception {
        mvc.perform(operation(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/ops/dlt", "POST,/api/v1/ops/dlt/replay",
            "GET,/api/v1/ops/outbox/dead", "POST,/api/v1/ops/outbox/dead/event-1/requeue"})
    void memberCannotUseRecoveryOperations(HttpMethod method, String path) throws Exception {
        mvc.perform(operation(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer MEMBER"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(dlt, outbox);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/ops/dlt", "POST,/api/v1/ops/dlt/replay",
            "GET,/api/v1/ops/outbox/dead", "POST,/api/v1/ops/outbox/dead/event-1/requeue"})
    void anonymousCannotUseRecoveryOperations(HttpMethod method, String path) throws Exception {
        mvc.perform(operation(method, path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(dlt, outbox);
    }

    @Test
    void adminCannotRequeueEveryDeadEventAtOnce() throws Exception {
        mvc.perform(operation(HttpMethod.POST, "/api/v1/ops/outbox/dead/requeue-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(dlt, outbox);
    }

    private static MockHttpServletRequestBuilder operation(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, path).param("topic", "stove.order.v1.DLT");
    }
}
