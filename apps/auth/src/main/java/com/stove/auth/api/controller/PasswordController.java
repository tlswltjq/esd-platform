package com.stove.auth.api.controller;

import com.stove.auth.core.service.PasswordService;
import com.stove.common.core.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PasswordController {
    private final PasswordService passwords;

    @PostMapping("/api/v1/auth/password")
    public ApiResponse<java.util.Map<String, Boolean>> change(java.security.Principal principal,
            @Valid @RequestBody ChangePassword request) {
        passwords.change(principal.getName(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok(java.util.Map.of("changed", true));
    }
    public record ChangePassword(@NotBlank String currentPassword,
                                 @NotBlank @Size(min = 12, max = 100) String newPassword) {}
}
