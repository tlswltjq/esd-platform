package com.stove.auth.api.controller;

import com.stove.auth.api.controller.dto.SignupRequest;
import com.stove.auth.api.controller.dto.SignupResponse;
import com.stove.auth.core.service.RegisterAccount;
import com.stove.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AccountController {

    private final RegisterAccount registerAccount;

    @GetMapping("/session")
    public ApiResponse<Map<String, String>> session(Principal principal) {
        return ApiResponse.ok(Map.of("subject", principal.getName()));
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
