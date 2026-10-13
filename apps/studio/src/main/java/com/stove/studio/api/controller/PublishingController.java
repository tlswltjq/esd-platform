package com.stove.studio.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.response.ApiResponse;
import com.stove.studio.api.controller.dto.CreatePricingRevisionRequest;
import com.stove.studio.api.controller.dto.CreateRatingRevisionRequest;
import com.stove.studio.api.controller.dto.CreateReleaseRequest;
import com.stove.studio.api.controller.dto.CreateStorePageRevisionRequest;
import com.stove.studio.api.controller.dto.CreateSubmissionRequest;
import com.stove.studio.api.controller.dto.PromoteReleaseRequest;
import com.stove.studio.api.controller.dto.ReleaseResponse;
import com.stove.studio.api.controller.dto.RescheduleReleaseRequest;
import com.stove.studio.api.controller.dto.RevisionResponse;
import com.stove.studio.api.controller.dto.StorePagePreviewResponse;
import com.stove.studio.api.controller.dto.SubmissionResponse;
import com.stove.studio.api.controller.dto.SubmissionReviewStatusResponse;
import com.stove.studio.api.controller.dto.TesterGrantRequest;
import com.stove.studio.api.controller.dto.TesterGrantResponse;
import com.stove.studio.core.service.access.InternalTesterService;
import com.stove.studio.core.service.project.WorkspaceService;
import com.stove.studio.core.service.release.ReleaseService;
import com.stove.studio.core.service.revision.RevisionService;
import com.stove.studio.core.service.submission.SubmissionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import com.stove.common.security.CurrentSubject;
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
                                                   @CurrentSubject String subject,
                                                   @Valid @RequestBody CreateStorePageRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.createStorePage(gameId, workspaceId,
                request.toDomain(), request.isDraft())));
    }

    @PutMapping("/{gameId}/store-page-revisions/{revisionId}")
    public ApiResponse<RevisionResponse> updateStorePageDraft(
            @PathVariable Long gameId, @PathVariable Long revisionId,
            @CurrentSubject String subject,
            @Valid @RequestBody CreateStorePageRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.updateStorePageDraft(
                gameId, revisionId, workspaceId, request.toDomain())));
    }

    @PostMapping("/{gameId}/store-page-revisions/{revisionId}/publish")
    public ApiResponse<RevisionResponse> publishStorePageDraft(
            @PathVariable Long gameId, @PathVariable Long revisionId,
            @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.publishStorePageDraft(
                gameId, revisionId, workspaceId)));
    }

    @GetMapping("/{gameId}/store-page-revisions/{revisionId}/preview")
    public ApiResponse<StorePagePreviewResponse> previewStorePage(
            @PathVariable Long gameId, @PathVariable Long revisionId,
            @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(StorePagePreviewResponse.from(
                revisionService.previewStorePage(gameId, revisionId, workspaceId), objectMapper));
    }

    @GetMapping("/{gameId}/store-page-revisions")
    public ApiResponse<List<RevisionResponse>> storePages(@PathVariable Long gameId,
                                                           @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(revisionService.storePages(gameId, workspaceId).stream()
                .map(RevisionResponse::from).toList());
    }

    @PostMapping("/{gameId}/pricing-revisions")
    public ApiResponse<RevisionResponse> pricing(@PathVariable Long gameId,
                                                 @CurrentSubject String subject,
                                                 @Valid @RequestBody CreatePricingRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(RevisionResponse.from(revisionService.createPricing(gameId, workspaceId, request.price())));
    }

    @GetMapping("/{gameId}/pricing-revisions")
    public ApiResponse<List<RevisionResponse>> pricings(@PathVariable Long gameId,
                                                        @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(revisionService.pricings(gameId, workspaceId).stream()
                .map(RevisionResponse::from).toList());
    }

    @PostMapping("/{gameId}/rating-revisions")
    public ApiResponse<RevisionResponse> rating(@PathVariable Long gameId,
                                                @CurrentSubject String subject,
                                                @Valid @RequestBody CreateRatingRevisionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(RevisionResponse.from(
                revisionService.createRating(gameId, workspaceId, request.questionnaire().toDomain())));
    }

    @GetMapping("/{gameId}/rating-revisions")
    public ApiResponse<List<RevisionResponse>> ratings(@PathVariable Long gameId,
                                                       @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(revisionService.ratings(gameId, workspaceId).stream()
                .map(RevisionResponse::from).toList());
    }

    @PostMapping("/{gameId}/submissions")
    public ApiResponse<SubmissionResponse> submit(@PathVariable Long gameId,
                                                  @CurrentSubject String subject,
                                                  @Valid @RequestBody CreateSubmissionRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(SubmissionResponse.from(submissionService.submit(gameId, workspaceId,
                request.metadataRevisionId(), request.pricingRevisionId(), request.ratingRevisionId(),
                request.buildId(), request.additionalBuildIds())));
    }

    @GetMapping("/submissions/{submissionId}")
    public ApiResponse<SubmissionResponse> submission(@PathVariable Long submissionId,
                                                      @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(SubmissionResponse.from(submissionService.requireOwned(submissionId, workspaceId)));
    }

    @GetMapping("/{gameId}/submissions")
    public ApiResponse<List<SubmissionResponse>> submissions(@PathVariable Long gameId,
                                                             @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(submissionService.findByGame(gameId, workspaceId).stream()
                .map(SubmissionResponse::from).toList());
    }

    @GetMapping("/submissions/{submissionId}/review-status")
    public ApiResponse<SubmissionReviewStatusResponse> reviewStatus(@PathVariable Long submissionId,
                                                                     @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        var review = submissionService.reviewStatus(submissionId, workspaceId);
        return ApiResponse.ok(SubmissionReviewStatusResponse.from(review.submission(), review.gates()));
    }

    @PostMapping("/submissions/{submissionId}/releases")
    public ApiResponse<ReleaseResponse> release(@PathVariable Long submissionId,
                                                @CurrentSubject String subject,
                                                @RequestBody(required = false) CreateReleaseRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(ReleaseResponse.from(releaseService.create(submissionId, workspaceId,
                request == null ? null : request.publishAt(), request == null ? null : request.timeZone(),
                request == null ? null : request.channel(), request == null ? null : request.changeType(),
                subject)));
    }

    @GetMapping("/{gameId}/releases")
    public ApiResponse<List<ReleaseResponse>> releases(@PathVariable Long gameId,
                                                       @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(releaseService.findByGame(gameId, workspaceId).stream()
                .map(ReleaseResponse::from).toList());
    }

    @PostMapping("/releases/{releaseId}/promote")
    public ApiResponse<ReleaseResponse> promote(@PathVariable Long releaseId,
                                                @CurrentSubject String subject,
                                                @Valid @RequestBody PromoteReleaseRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(ReleaseResponse.from(releaseService.promote(releaseId, workspaceId,
                request.targetChannel(), request.publishAt(), request.timeZone(), subject)));
    }

    @PostMapping("/releases/{releaseId}/rollback")
    public ApiResponse<ReleaseResponse> rollback(@PathVariable Long releaseId,
                                                 @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(ReleaseResponse.from(
                releaseService.rollback(releaseId, workspaceId, subject)));
    }

    @PostMapping("/releases/{releaseId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long releaseId, @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        releaseService.cancel(releaseId, workspaceId, subject);
        return ApiResponse.ok();
    }

    @PostMapping("/releases/{releaseId}/reschedule")
    public ApiResponse<ReleaseResponse> reschedule(
            @PathVariable Long releaseId, @CurrentSubject String subject,
            @Valid @RequestBody RescheduleReleaseRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(ReleaseResponse.from(releaseService.reschedule(releaseId, workspaceId,
                request.publishAt(), request.timeZone(), subject)));
    }

    @PostMapping("/{gameId}/testers")
    public ApiResponse<TesterGrantResponse> grantTester(
            @PathVariable Long gameId, @CurrentSubject String subject,
            @Valid @RequestBody TesterGrantRequest request) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(TesterGrantResponse.from(testerService.grant(gameId, workspaceId,
                request.testerSubject(), request.channel(), subject)));
    }

    @GetMapping("/{gameId}/testers")
    public ApiResponse<List<TesterGrantResponse>> testers(
            @PathVariable Long gameId, @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        return ApiResponse.ok(testerService.grants(gameId, workspaceId).stream()
                .map(TesterGrantResponse::from).toList());
    }

    @PostMapping("/{gameId}/testers/{grantId}/revoke")
    public ApiResponse<Void> revokeTester(
            @PathVariable Long gameId, @PathVariable Long grantId,
            @CurrentSubject String subject) {
        Long workspaceId = workspaceService.getOrCreatePersonal(subject).getId();
        testerService.revoke(gameId, grantId, workspaceId, subject);
        return ApiResponse.ok();
    }
}
