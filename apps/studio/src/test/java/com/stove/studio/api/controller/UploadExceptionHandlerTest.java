package com.stove.studio.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

class UploadExceptionHandlerTest {

    private final UploadExceptionHandler handler = new UploadExceptionHandler();

    /** 결정 studio-upload-413-code: 상태(413)와 본문의 오류 코드가 같은 뜻을 말한다. */
    @Test
    void anUploadOverTheMultipartLimitAnswers413WithTheMatchingErrorCode() {
        ResponseEntity<ApiResponse<Void>> response = handler.tooLarge(new MaxUploadSizeExceededException(1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.code());
        assertThat(response.getBody().error().message()).isEqualTo("파일 최대 크기를 초과했습니다.");
    }

    @Test
    void aMissingFilePartStaysAnInvalidRequest() {
        ResponseEntity<ApiResponse<Void>> response = handler.missingFile(new MissingServletRequestPartException("file"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INVALID_REQUEST.code());
    }
}
