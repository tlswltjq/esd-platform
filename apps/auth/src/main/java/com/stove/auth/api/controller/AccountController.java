package com.stove.auth.api.controller;

import com.stove.auth.api.controller.dto.SignupRequest;
import com.stove.auth.api.controller.dto.SignupResponse;
import com.stove.auth.core.service.RegisterAccount;
import com.stove.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AccountController {

    private final RegisterAccount registerAccount;

    @org.springframework.web.bind.annotation.GetMapping("/session")
    public ApiResponse<java.util.Map<String, String>> session(java.security.Principal principal) {
        return ApiResponse.ok(java.util.Map.of("subject", principal.getName()));
    }

    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(SignupResponse.from(
                registerAccount.registerCreator(request.email(), request.password())));
    }

    @PostMapping("/signup/member")
    public ApiResponse<SignupResponse> signupMember(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(SignupResponse.from(
                registerAccount.registerMember(request.email(), request.password())));
    }
}
