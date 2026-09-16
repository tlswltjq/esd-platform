package com.stove.studio.api.controller;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.BuildResponse;
import com.stove.studio.api.controller.dto.CompleteUploadRequest;
import com.stove.studio.api.controller.dto.CreateUploadSessionRequest;
import com.stove.studio.api.controller.dto.UploadSessionResponse;
import com.stove.studio.config.ProjectCredentialPrincipal;
import com.stove.studio.core.service.BuildValidationDispatcherService;
import com.stove.studio.core.service.UploadSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/ci/projects/{gameId}")
public class CiUploadController {

    private final UploadSessionService uploadSessionService;
    private final BuildValidationDispatcherService buildValidationService;

    @PostMapping("/upload-sessions")
    public ApiResponse<UploadSessionResponse> create(
            @PathVariable Long gameId,
            @AuthenticationPrincipal ProjectCredentialPrincipal principal,
            @Valid @RequestBody CreateUploadSessionRequest request) {
        requireScope(gameId, principal);
        return ApiResponse.ok(UploadSessionResponse.from(
                uploadSessionService.create(gameId, principal.workspaceId(), request.toCommand())));
    }

    @PostMapping("/upload-sessions/{sessionId}/complete")
    public ApiResponse<BuildResponse> complete(
            @PathVariable Long gameId, @PathVariable Long sessionId,
            @AuthenticationPrincipal ProjectCredentialPrincipal principal,
            @Valid @RequestBody CompleteUploadRequest request) {
        requireScope(gameId, principal);
        Long buildId = uploadSessionService.complete(sessionId, principal.workspaceId(), request.toParts());
        buildValidationService.dispatch(buildId);
        return ApiResponse.ok(BuildResponse.from(
                uploadSessionService.requireOwnedBuild(buildId, principal.workspaceId())));
    }

    /** CI polling용 읽기 API. 자격증명이 발급된 단일 프로젝트의 상태만 노출한다. */
    @GetMapping("/builds/{buildId}")
    public ApiResponse<BuildResponse> status(
            @PathVariable Long gameId, @PathVariable Long buildId,
            @AuthenticationPrincipal ProjectCredentialPrincipal principal) {
        requireScope(gameId, principal);
        var build = uploadSessionService.requireOwnedBuild(buildId, principal.workspaceId());
        if (!gameId.equals(build.getGameId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 프로젝트의 빌드입니다.");
        }
        return ApiResponse.ok(BuildResponse.from(build));
    }

    private void requireScope(Long gameId, ProjectCredentialPrincipal principal) {
        if (principal == null || !gameId.equals(principal.gameId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 프로젝트에는 사용할 수 없습니다.");
        }
    }
}
