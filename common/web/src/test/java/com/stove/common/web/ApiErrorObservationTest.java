package com.stove.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.stove.common.web.error.ApiErrorCode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * HTTP 요청 관측에 오류 코드가 실리는지 본다(결정 error-observability).
 *
 * <p>상태 코드만으로는 어떤 업무 오류였는지 알 수 없다(409 하나에 여러 코드가 있다). 요청 지표({@code http.server.requests})에
 * {@code error.code} 태그를 달아 코드별 추이를 로그 없이 본다. Prometheus 는 같은 이름의 지표가 같은 태그 집합을 갖기를
 * 요구하므로 성공한 요청에도 {@code none} 으로 태그가 있어야 한다.
 */
class ApiErrorObservationTest {

    @RestController
    static class Endpoints {

        @GetMapping("/ok")
        ApiResponse<String> ok() {
            return ApiResponse.ok("done");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        @GetMapping("/application-error")
        String applicationError() {
            throw new BusinessException(new ApiErrorCode() {
                public String code() { return "PRICE_MISMATCH"; }
                public int statusCode() { return 409; }
                public String defaultMessage() { return "가격이 바뀌었습니다."; }
            });
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("아무도 처리하지 않는 예외");
        }
    }

    /** 끝난 관측의 {@code error.code} 값을 모은다. */
    private final List<String> recorded = new ArrayList<>();

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new Endpoints())
            .setControllerAdvice(new GlobalExceptionHandler(), new ApiErrorCodeAdvice())
            .addFilters(new ServerHttpObservationFilter(recordingRegistry(), new ApiErrorObservationConvention()))
            .build();

    private ObservationRegistry recordingRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                KeyValue errorCode = context.getLowCardinalityKeyValue(ApiErrorObservationConvention.ERROR_CODE);
                recorded.add(errorCode == null ? null : errorCode.getValue());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return registry;
    }

    @Test
    @DisplayName("성공한 요청은 error.code 가 none 이다")
    void successHasNoErrorCode() throws Exception {
        mvc.perform(get("/ok"));

        assertThat(recorded).containsExactly(ApiErrorObservationConvention.NO_ERROR);
    }

    @Test
    @DisplayName("공통 오류 코드가 error.code 로 실린다")
    void commonErrorCodeIsRecorded() throws Exception {
        mvc.perform(get("/conflict"));

        assertThat(recorded).containsExactly(ErrorCode.CONFLICT.name());
    }

    @Test
    @DisplayName("앱이 정의한 오류 코드도 error.code 로 실린다")
    void applicationErrorCodeIsRecorded() throws Exception {
        mvc.perform(get("/application-error"));

        assertThat(recorded).containsExactly("PRICE_MISMATCH");
    }

    @Test
    @DisplayName("처리되지 않은 예외는 INTERNAL_ERROR 로 실린다")
    void unexpectedIsRecordedAsInternalError() throws Exception {
        mvc.perform(get("/unexpected"));

        assertThat(recorded).containsExactly(ErrorCode.INTERNAL_ERROR.name());
    }

    @Test
    @DisplayName("표준 MVC 예외도 그 오류 코드로 실린다")
    void standardMvcExceptionIsRecorded() throws Exception {
        mvc.perform(post("/ok"));

        assertThat(recorded).containsExactly(ErrorCode.METHOD_NOT_ALLOWED.name());
    }
}
