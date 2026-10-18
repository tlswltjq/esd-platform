package com.stove.auth.api.controller;

import com.stove.auth.core.service.ChangePassword;
import com.stove.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PasswordController {
    private final ChangePassword changePassword;

    @PostMapping("/api/v1/auth/password")
    public ApiResponse<Map<String, Boolean>> change(Principal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        changePassword.execute(principal.getName(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok(Map.of("changed", true));
    }
    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                 @NotBlank @Size(min = 12, max = 100) String newPassword) {}
}
