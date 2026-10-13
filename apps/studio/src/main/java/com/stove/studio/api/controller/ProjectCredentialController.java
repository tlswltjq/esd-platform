package com.stove.studio.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.studio.api.controller.dto.CreateProjectCredentialRequest;
import com.stove.studio.api.controller.dto.IssuedProjectCredentialResponse;
import com.stove.studio.api.controller.dto.ProjectCredentialResponse;
import com.stove.studio.core.service.access.ProjectCredentialService;
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
@RequestMapping("/api/v1/studio/projects/{gameId}/credentials")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class ProjectCredentialController {

    private final ProjectCredentialService credentialService;
    private final WorkspaceService workspaceService;

    @PostMapping
    public ApiResponse<IssuedProjectCredentialResponse> issue(
            @PathVariable Long gameId, @CurrentSubject String subject,
            @Valid @RequestBody CreateProjectCredentialRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(IssuedProjectCredentialResponse.from(
                credentialService.issue(gameId, workspaceId, request.name(), request.expiresAt())));
    }

    @GetMapping
    public ApiResponse<List<ProjectCredentialResponse>> list(
            @PathVariable Long gameId, @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(credentialService.list(gameId, workspaceId).stream()
                .map(ProjectCredentialResponse::from).toList());
    }

    @DeleteMapping("/{credentialId}")
    public ApiResponse<Void> revoke(@PathVariable Long gameId, @PathVariable Long credentialId,
                                    @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        credentialService.revoke(gameId, credentialId, workspaceId);
        return ApiResponse.ok();
    }
}
