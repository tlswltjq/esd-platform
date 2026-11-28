package com.stove.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stove.common.web.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Spring MVC 가 던지는 표준 예외가 {@code ApiResponse} 와 제 상태 코드로 나가는지 본다(결정 mvc-standard-exceptions).
 *
 * <p>{@code @ExceptionHandler(Exception.class)} 가 있으면 따로 받지 않은 MVC 예외가 전부 500 이 된다(D-015).
 * 예외를 하나씩 나열하는 대신 {@code ResponseEntityExceptionHandler} 에 맡기므로, 여기서는 나열에서 빠져 있던 것(405·415·413·
 * 파트 누락·파라미터 제약)과 이미 받던 것의 메시지가 그대로인지를 함께 본다.
 */
class StandardMvcExceptionStatusTest {

    record Body(@NotBlank String name) {
    }

    @RestController
    static class Endpoints {

        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@Valid @RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/page")
        String page(@RequestParam int page) {
            return String.valueOf(page);
        }

        @GetMapping("/keyed")
        String keyed(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }

        @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String upload(@RequestPart("file") MultipartFile file) {
            return file.getName();
        }

        @GetMapping("/too-large")
        String tooLarge() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/sized")
        String sized(@RequestParam @Min(1) int size) {
            return String.valueOf(size);
        }
    }

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new Endpoints())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("허용하지 않은 HTTP 메서드는 405 이고 Allow 헤더가 붙는다")
    void methodNotAllowed() throws Exception {
        mvc.perform(get("/body"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.METHOD_NOT_ALLOWED.name()))
                .andExpect(jsonPath("$.error.message").value(ErrorCode.METHOD_NOT_ALLOWED.defaultMessage()));
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type 은 415 다")
    void unsupportedMediaType() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name()));
    }

    @Test
    @DisplayName("업로드 크기 초과는 413 이다")
    void payloadTooLarge() throws Exception {
        mvc.perform(get("/too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.PAYLOAD_TOO_LARGE.name()));
    }

    @Test
    @DisplayName("멀티파트 파트가 빠지면 400 이다")
    void missingPart() throws Exception {
        mvc.perform(multipart("/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("@Validated 없는 컨트롤러의 파라미터 제약 위반은 400 이다")
    void parameterConstraint() throws Exception {
        mvc.perform(get("/sized").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("본문 검증 실패는 400 이고 첫 필드 오류를 알려 준다")
    void bodyValidation() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.error.message").value(startsWith("name: ")));
    }

    @Test
    @DisplayName("깨진 본문은 400 이다")
    void unreadableBody() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("필수 파라미터 누락은 400 이고 어느 파라미터인지 알려 준다")
    void missingParameter() throws Exception {
        mvc.perform(get("/page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.error.message").value(containsString("'page'")));
    }

    @Test
    @DisplayName("파라미터 타입 불일치는 400 이다")
    void typeMismatch() throws Exception {
        mvc.perform(get("/page").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_REQUEST.name()));
    }

    @Test
    @DisplayName("필수 헤더 누락은 400 이고 어느 헤더인지 알려 준다")
    void missingHeader() throws Exception {
        mvc.perform(get("/keyed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.error.message").value(containsString("Idempotency-Key")));
    }
}
