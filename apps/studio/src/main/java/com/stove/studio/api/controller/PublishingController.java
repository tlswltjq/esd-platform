package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.CreatePricingRevisionRequest;
import com.stove.studio.api.controller.dto.CreateRatingRevisionRequest;
import com.stove.studio.api.controller.dto.CreateStorePageRevisionRequest;
import com.stove.studio.api.controller.dto.CreateSubmissionRequest;
import com.stove.studio.api.controller.dto.RevisionResponse;
import com.stove.studio.api.controller.dto.SubmissionResponse;
import com.stove.studio.api.controller.dto.CreateReleaseRequest;
import com.stove.studio.api.controller.dto.ReleaseResponse;
import com.stove.studio.core.service.ReleaseService;
import com.stove.studio.core.service.RevisionService;
import com.stove.studio.core.service.SubmissionService;
import com.stove.studio.core.service.WorkspaceService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/projects")
@SecurityRequirement(name = "oauth2", scopes = "studio")
public class PublishingController {

    private final WorkspaceService workspaceService;
    private final RevisionService revisionService;
    private final SubmissionService submissionService;
    private final ReleaseService releaseService;

    @PostMapping("/{gameId}/store-page-revisions")
    public ApiResponse<RevisionResponse> storePage(@PathVariable Long gameId,
                                                   @AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateStorePageRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.createStorePage(gameId, workspaceId,
                request.title(), request.shortDescription(), request.platform(), request.minimumRequirements())));
    }

    @PostMapping("/{gameId}/pricing-revisions")
    public ApiResponse<RevisionResponse> pricing(@PathVariable Long gameId,
                                                 @AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody CreatePricingRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.createPricing(gameId, workspaceId, request.price())));
    }

    @PostMapping("/{gameId}/rating-revisions")
    public ApiResponse<RevisionResponse> rating(@PathVariable Long gameId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody CreateRatingRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(RevisionResponse.from(
                revisionService.createRating(gameId, workspaceId, request.questionnaire().toDomain())));
    }

    @PostMapping("/{gameId}/submissions")
    public ApiResponse<SubmissionResponse> submit(@PathVariable Long gameId,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateSubmissionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(SubmissionResponse.from(submissionService.submit(gameId, workspaceId,
                request.metadataRevisionId(), request.pricingRevisionId(), request.ratingRevisionId(),
                request.buildId())));
    }

    @GetMapping("/submissions/{submissionId}")
    public ApiResponse<SubmissionResponse> submission(@PathVariable Long submissionId,
                                                      @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(SubmissionResponse.from(submissionService.requireOwned(submissionId, workspaceId)));
    }

    @PostMapping("/submissions/{submissionId}/releases")
    public ApiResponse<ReleaseResponse> release(@PathVariable Long submissionId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @RequestBody(required = false) CreateReleaseRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(ReleaseResponse.from(releaseService.create(submissionId, workspaceId,
                request == null ? null : request.publishAt(), jwt.getSubject())));
    }

    @PostMapping("/releases/{releaseId}/rollback")
    public ApiResponse<ReleaseResponse> rollback(@PathVariable Long releaseId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(ReleaseResponse.from(
                releaseService.rollback(releaseId, workspaceId, jwt.getSubject())));
    }

    @PostMapping("/releases/{releaseId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long releaseId, @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        releaseService.cancel(releaseId, workspaceId, jwt.getSubject());
        return ApiResponse.ok();
    }
}
