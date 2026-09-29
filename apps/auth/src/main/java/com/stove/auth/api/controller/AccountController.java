package com.stove.auth.api.controller;

import com.stove.auth.api.controller.dto.SignupRequest;
import com.stove.auth.api.controller.dto.SignupResponse;
import com.stove.auth.core.service.UserAccountService;
import com.stove.common.core.response.ApiResponse;
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

    private final UserAccountService userAccountService;

    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(SignupResponse.from(
                userAccountService.signup(request.email(), request.password())));
    }
}
