package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.BuildResponse;
import com.stove.studio.api.controller.dto.CreateProjectRequest;
import com.stove.studio.api.controller.dto.ProjectResponse;
import com.stove.studio.api.controller.dto.UploadBuildRequest;
import com.stove.studio.core.service.StudioService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** sellerId 는 실제로는 스튜디오 계정 토큰에서 주입된다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/games")
public class StudioController {

    private final StudioService studioService;

    @PostMapping
    public ApiResponse<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(ProjectResponse.from(studioService.createProject(request.toCommand())));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(@RequestHeader("X-Seller-Id") Long sellerId) {
        return ApiResponse.ok(studioService.getProjects(sellerId).stream()
                .map(ProjectResponse::from)
                .toList());
    }

    /** 등급분류 심의 신청 */
    @PostMapping("/{gameId}/submit")
    public ApiResponse<Void> submit(@PathVariable Long gameId, @RequestHeader("X-Seller-Id") Long sellerId) {
        studioService.submitForReview(gameId, sellerId);
        return ApiResponse.ok();
    }

    @PostMapping("/{gameId}/builds")
    public ApiResponse<BuildResponse> uploadBuild(@PathVariable Long gameId,
                                                  @RequestHeader("X-Seller-Id") Long sellerId,
                                                  @Valid @RequestBody UploadBuildRequest request) {
        return ApiResponse.ok(BuildResponse.from(
                studioService.uploadBuild(gameId, sellerId, request.toCommand())));
    }

    @GetMapping("/{gameId}/builds")
    public ApiResponse<List<BuildResponse>> builds(@PathVariable Long gameId) {
        return ApiResponse.ok(studioService.getBuilds(gameId).stream()
                .map(BuildResponse::from)
                .toList());
    }
}
