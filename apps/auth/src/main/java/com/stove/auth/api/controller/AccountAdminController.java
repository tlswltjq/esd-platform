package com.stove.auth.api.controller;

import com.stove.auth.core.domain.AccountStatus;
import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.service.ManageAccount;
import com.stove.common.web.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자의 계정 상태·역할 관리 API. 모든 경로는 게이트웨이에서 ADMIN 역할을 요구한다.
 * 수행 주체(actor)는 요청자의 JWT sub로 식별하며, 자기 계정 변경 금지와 마지막 ADMIN 보호는
 * {@link ManageAccount}가 판정한다.
 */
@RestController
@RequiredArgsConstructor
public class AccountAdminController {

    private final ManageAccount manageAccount;

    @PostMapping("/api/v1/admin/accounts/{subject}/status")
    public ApiResponse<Map<String, Boolean>> changeStatus(Principal principal,
            @PathVariable String subject, @Valid @RequestBody StatusRequest request) {
        manageAccount.changeStatus(principal.getName(), subject, request.status());
        return ApiResponse.ok(Map.of("changed", true));
    }

    @PostMapping("/api/v1/admin/accounts/{subject}/roles/grant")
    public ApiResponse<Map<String, Boolean>> grantRole(Principal principal,
            @PathVariable String subject, @Valid @RequestBody RoleRequest request) {
        manageAccount.grantRole(principal.getName(), subject, request.role());
        return ApiResponse.ok(Map.of("granted", true));
    }

    @PostMapping("/api/v1/admin/accounts/{subject}/roles/revoke")
    public ApiResponse<Map<String, Boolean>> revokeRole(Principal principal,
            @PathVariable String subject, @Valid @RequestBody RoleRequest request) {
        manageAccount.revokeRole(principal.getName(), subject, request.role());
        return ApiResponse.ok(Map.of("revoked", true));
    }

    public record StatusRequest(@NotNull AccountStatus status) {}

    public record RoleRequest(@NotNull PlatformRole role) {}
}
