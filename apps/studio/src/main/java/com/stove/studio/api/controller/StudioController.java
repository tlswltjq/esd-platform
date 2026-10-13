package com.stove.studio.api.controller;

import com.stove.common.web.response.ApiResponse;
import com.stove.studio.api.controller.dto.BuildResponse;
import com.stove.studio.api.controller.dto.CompleteUploadRequest;
import com.stove.studio.api.controller.dto.CreateProjectRequest;
import com.stove.studio.api.controller.dto.CreateUploadSessionRequest;
import com.stove.studio.api.controller.dto.ProductFamilyResponse;
import com.stove.studio.api.controller.dto.ProjectResponse;
import com.stove.studio.api.controller.dto.UploadBuildRequest;
import com.stove.studio.api.controller.dto.UploadSessionResponse;
import com.stove.studio.core.service.build.BuildValidationDispatcherService;
import com.stove.studio.core.service.build.GameBuildService;
import com.stove.studio.core.service.build.UploadSessionService;
import com.stove.studio.core.service.project.GameProjectService;
import com.stove.studio.core.service.project.WorkspaceService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import com.stove.common.security.CurrentSubject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/games")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class StudioController {

    private final GameProjectService gameProjectService;
    private final GameBuildService gameBuildService;
    private final WorkspaceService workspaceService;
    private final UploadSessionService uploadSessionService;
    private final BuildValidationDispatcherService buildValidationService;

    @PostMapping
    public ApiResponse<ProjectResponse> create(@CurrentSubject String subject,
                                               @Valid @RequestBody CreateProjectRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(ProjectResponse.from(gameProjectService.create(request.toCommand(workspaceId))));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(@CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(gameProjectService.findBySeller(workspaceId).stream()
                .map(ProjectResponse::from)
                .toList());
    }

    @GetMapping("/{gameId}/family")
    public ApiResponse<ProductFamilyResponse> family(@PathVariable Long gameId,
                                                    @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        var family = gameProjectService.family(gameId, workspaceId);
        return ApiResponse.ok(ProductFamilyResponse.from(family.product(), family.parent(),
                family.children(), family.components(), family.bundles()));
    }

    /** 등급분류 심의 신청 */
    @PostMapping("/{gameId}/submit")
    public ApiResponse<Void> submit(@PathVariable Long gameId, @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        gameProjectService.submitForReview(gameId, workspaceId);
        return ApiResponse.ok();
    }

    @PostMapping("/{gameId}/builds")
    public ApiResponse<BuildResponse> uploadBuild(@PathVariable Long gameId,
                                                  @CurrentSubject String subject,
                                                  @Valid @RequestBody UploadBuildRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(BuildResponse.from(
                gameBuildService.upload(gameId, workspaceId, request.toCommand())));
    }

    @GetMapping("/{gameId}/builds")
    public ApiResponse<List<BuildResponse>> builds(@PathVariable Long gameId,
                                                   @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(gameBuildService.findByGame(gameId, workspaceId).stream()
                .map(BuildResponse::from)
                .toList());
    }

    @PostMapping("/{gameId}/upload-sessions")
    public ApiResponse<UploadSessionResponse> createUploadSession(
            @PathVariable Long gameId,
            @CurrentSubject String subject,
            @Valid @RequestBody CreateUploadSessionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(UploadSessionResponse.from(
                uploadSessionService.create(gameId, workspaceId, request.toCommand())));
    }

    @PostMapping("/upload-sessions/{sessionId}/complete")
    public ApiResponse<BuildResponse> completeUpload(
            @PathVariable Long sessionId,
            @CurrentSubject String subject,
            @Valid @RequestBody CompleteUploadRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        Long buildId = uploadSessionService.complete(sessionId, workspaceId, request.toParts());
        buildValidationService.dispatch(buildId);
        return ApiResponse.ok(BuildResponse.from(uploadSessionService.requireOwnedBuild(buildId, workspaceId)));
    }
}
