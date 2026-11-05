package com.stove.auth.api.controller;

import com.stove.auth.core.service.RecoverPassword;
import com.stove.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분실 비밀번호 복구 API. 두 엔드포인트 모두 로그인 없이 호출한다.
 *
 * <p>요청 응답은 가입 여부와 무관하게 항상 동일하다(계정 존재 여부를 숨긴다). 실행은 토큰이 유효할
 * 때만 성공하며, 실패 사유는 "유효하지 않거나 만료"로 통일해 토큰 상태를 드러내지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class PasswordResetController {

    private final RecoverPassword recoverPassword;

    @PostMapping("/api/v1/auth/password/reset-request")
    public ApiResponse<Map<String, Boolean>> requestReset(@Valid @RequestBody ResetRequest request) {
        recoverPassword.request(request.email());
        // 가입 여부를 드러내지 않도록 언제나 동일하게 성공으로 응답한다.
        return ApiResponse.ok(Map.of("accepted", true));
    }

    @PostMapping("/api/v1/auth/password/reset")
    public ApiResponse<Map<String, Boolean>> reset(@Valid @RequestBody ResetConfirm request) {
        recoverPassword.reset(request.token(), request.newPassword());
        return ApiResponse.ok(Map.of("reset", true));
    }

    public record ResetRequest(@NotBlank @Email String email) {}

    public record ResetConfirm(@NotBlank String token,
                               @NotBlank @Size(min = 12, max = 100) String newPassword) {}
}
