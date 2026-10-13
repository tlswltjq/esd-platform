package com.stove.studio.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.studio.api.controller.dto.CiTrustPolicyResponse;
import com.stove.studio.api.controller.dto.CreateCiTrustPolicyRequest;
import com.stove.studio.core.service.access.CiTrustPolicyService;
import com.stove.studio.core.service.project.WorkspaceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import com.stove.common.security.CurrentSubject;
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
            @PathVariable Long gameId, @CurrentSubject String subject,
            @Valid @RequestBody CreateCiTrustPolicyRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(CiTrustPolicyResponse.from(policyService.create(gameId, workspaceId,
                request.provider(), request.repository(), request.refPattern(), request.platform(),
                request.protectedRefPattern(), request.requiredEnvironment())));
    }

    @GetMapping
    public ApiResponse<List<CiTrustPolicyResponse>> list(
            @PathVariable Long gameId, @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(policyService.list(gameId, workspaceId).stream()
                .map(CiTrustPolicyResponse::from).toList());
    }

    @DeleteMapping("/{policyId}")
    public ApiResponse<Void> delete(@PathVariable Long gameId, @PathVariable Long policyId,
                                    @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        policyService.delete(gameId, policyId, workspaceId);
        return ApiResponse.ok();
    }
}
