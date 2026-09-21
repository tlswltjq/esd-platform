package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.CreateProjectCredentialRequest;
import com.stove.studio.api.controller.dto.IssuedProjectCredentialResponse;
import com.stove.studio.api.controller.dto.ProjectCredentialResponse;
import com.stove.studio.core.service.ProjectCredentialService;
import com.stove.studio.core.service.WorkspaceService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
@RequestMapping("/api/v1/studio/projects/{gameId}/credentials")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class ProjectCredentialController {

    private final ProjectCredentialService credentialService;
    private final WorkspaceService workspaceService;

    @PostMapping
    public ApiResponse<IssuedProjectCredentialResponse> issue(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProjectCredentialRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(IssuedProjectCredentialResponse.from(
                credentialService.issue(gameId, workspaceId, request.name(), request.expiresAt())));
    }

    @GetMapping
    public ApiResponse<List<ProjectCredentialResponse>> list(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(credentialService.list(gameId, workspaceId).stream()
                .map(ProjectCredentialResponse::from).toList());
    }

    @DeleteMapping("/{credentialId}")
    public ApiResponse<Void> revoke(@PathVariable Long gameId, @PathVariable Long credentialId,
                                    @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        credentialService.revoke(gameId, credentialId, workspaceId);
        return ApiResponse.ok();
    }
}
