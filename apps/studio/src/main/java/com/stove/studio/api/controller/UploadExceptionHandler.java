package com.stove.studio.api.controller;

import com.stove.common.web.error.ErrorCode;
import com.stove.common.web.response.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
@Order(-100)
public class UploadExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException failure) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, "파일 최대 크기를 초과했습니다."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> missingFile(
            MissingServletRequestPartException failure) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, "file 업로드 항목이 필요합니다."));
    }
}
