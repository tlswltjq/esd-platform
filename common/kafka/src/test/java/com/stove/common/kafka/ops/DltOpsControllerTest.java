package com.stove.common.kafka.ops;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.GlobalExceptionHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DltOpsControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST"})
    void anUnknownDltIsAnInvalidRequestRatherThanAnInternalFailure(String method) throws Exception {
        DltOpsService service = mock(DltOpsService.class);
        when(service.peek(anyString(), anyInt())).thenThrow(new IllegalArgumentException("그런 토픽이 없다"));
        when(service.replay(anyString(), anyInt())).thenThrow(new IllegalArgumentException("그런 토픽이 없다"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DltOpsController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = method.equals("GET") ? "/api/v1/ops/dlt" : "/api/v1/ops/dlt/replay";

        mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .param("topic", "missing.DLT")).andExpect(status().isBadRequest());
    }
}
