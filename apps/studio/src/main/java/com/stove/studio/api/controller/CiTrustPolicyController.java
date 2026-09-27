package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.CiTrustPolicyResponse;
import com.stove.studio.api.controller.dto.CreateCiTrustPolicyRequest;
import com.stove.studio.core.service.CiTrustPolicyService;
import com.stove.studio.core.service.WorkspaceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/projects/{gameId}/ci-trust-policies")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class CiTrustPolicyController {
    private final CiTrustPolicyService policyService;
    private final WorkspaceService workspaceService;

    @PostMapping
    public ApiResponse<CiTrustPolicyResponse> create(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCiTrustPolicyRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(CiTrustPolicyResponse.from(policyService.create(gameId, workspaceId,
                request.provider(), request.repository(), request.refPattern(), request.platform(),
                request.protectedRefPattern(), request.requiredEnvironment())));
    }

    @GetMapping
    public ApiResponse<List<CiTrustPolicyResponse>> list(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(policyService.list(gameId, workspaceId).stream()
                .map(CiTrustPolicyResponse::from).toList());
    }

    @DeleteMapping("/{policyId}")
    public ApiResponse<Void> delete(@PathVariable Long gameId, @PathVariable Long policyId,
                                    @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        policyService.delete(gameId, policyId, workspaceId);
        return ApiResponse.ok();
    }
}
