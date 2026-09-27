package com.stove.studio.api.controller;

import com.stove.common.core.response.ApiResponse;
import com.stove.studio.api.controller.dto.CreatePricingRevisionRequest;
import com.stove.studio.api.controller.dto.CreateRatingRevisionRequest;
import com.stove.studio.api.controller.dto.CreateStorePageRevisionRequest;
import com.stove.studio.api.controller.dto.CreateSubmissionRequest;
import com.stove.studio.api.controller.dto.RevisionResponse;
import com.stove.studio.api.controller.dto.SubmissionResponse;
import com.stove.studio.api.controller.dto.StorePagePreviewResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.studio.api.controller.dto.CreateReleaseRequest;
import com.stove.studio.api.controller.dto.ReleaseResponse;
import com.stove.studio.api.controller.dto.RescheduleReleaseRequest;
import com.stove.studio.api.controller.dto.PromoteReleaseRequest;
import com.stove.studio.api.controller.dto.TesterGrantRequest;
import com.stove.studio.api.controller.dto.TesterGrantResponse;
import com.stove.studio.core.service.ReleaseService;
import com.stove.studio.core.service.RevisionService;
import com.stove.studio.core.service.SubmissionService;
import com.stove.studio.core.service.WorkspaceService;
import com.stove.studio.core.service.InternalTesterService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final ObjectMapper objectMapper;
    private final InternalTesterService testerService;

    @PostMapping("/{gameId}/store-page-revisions")
    public ApiResponse<RevisionResponse> storePage(@PathVariable Long gameId,
                                                   @AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateStorePageRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.createStorePage(gameId, workspaceId,
                request.toDomain(), request.isDraft())));
    }

    @PutMapping("/{gameId}/store-page-revisions/{revisionId}")
    public ApiResponse<RevisionResponse> updateStorePageDraft(
            @PathVariable Long gameId, @PathVariable Long revisionId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStorePageRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.updateStorePageDraft(
                gameId, revisionId, workspaceId, request.toDomain())));
    }

    @PostMapping("/{gameId}/store-page-revisions/{revisionId}/publish")
    public ApiResponse<RevisionResponse> publishStorePageDraft(
            @PathVariable Long gameId, @PathVariable Long revisionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.publishStorePageDraft(
                gameId, revisionId, workspaceId)));
    }

    @GetMapping("/{gameId}/store-page-revisions/{revisionId}/preview")
    public ApiResponse<StorePagePreviewResponse> previewStorePage(
            @PathVariable Long gameId, @PathVariable Long revisionId,
            @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(StorePagePreviewResponse.from(
                revisionService.previewStorePage(gameId, revisionId, workspaceId), objectMapper));
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
                request == null ? null : request.publishAt(), request == null ? null : request.timeZone(),
                request == null ? null : request.channel(), request == null ? null : request.changeType(),
                jwt.getSubject())));
    }

    @PostMapping("/releases/{releaseId}/promote")
    public ApiResponse<ReleaseResponse> promote(@PathVariable Long releaseId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody PromoteReleaseRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(ReleaseResponse.from(releaseService.promote(releaseId, workspaceId,
                request.targetChannel(), request.publishAt(), request.timeZone(), jwt.getSubject())));
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

    @PostMapping("/releases/{releaseId}/reschedule")
    public ApiResponse<ReleaseResponse> reschedule(
            @PathVariable Long releaseId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RescheduleReleaseRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(ReleaseResponse.from(releaseService.reschedule(releaseId, workspaceId,
                request.publishAt(), request.timeZone(), jwt.getSubject())));
    }

    @PostMapping("/{gameId}/testers")
    public ApiResponse<TesterGrantResponse> grantTester(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TesterGrantRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(TesterGrantResponse.from(testerService.grant(gameId, workspaceId,
                request.testerSubject(), request.channel(), jwt.getSubject())));
    }

    @GetMapping("/{gameId}/testers")
    public ApiResponse<List<TesterGrantResponse>> testers(
            @PathVariable Long gameId, @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        return ApiResponse.ok(testerService.grants(gameId, workspaceId).stream()
                .map(TesterGrantResponse::from).toList());
    }

    @PostMapping("/{gameId}/testers/{grantId}/revoke")
    public ApiResponse<Void> revokeTester(
            @PathVariable Long gameId, @PathVariable Long grantId,
            @AuthenticationPrincipal Jwt jwt) {
        Long workspaceId = workspaceService.getOrCreatePersonal(jwt.getSubject()).getId();
        testerService.revoke(gameId, grantId, workspaceId, jwt.getSubject());
        return ApiResponse.ok();
    }
}
