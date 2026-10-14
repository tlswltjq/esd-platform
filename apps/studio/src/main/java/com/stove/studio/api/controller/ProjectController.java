package com.stove.studio.api.controller;

import com.stove.common.security.CurrentSubject;
import com.stove.common.web.response.ApiResponse;
import com.stove.studio.api.controller.dto.*;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.service.project.*;
import com.stove.studio.core.service.release.RequestProjectRelease;
import com.stove.studio.core.service.review.RequestProjectReview;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/studio/projects")
public class ProjectController {
    private final CreateProject createProject;
    private final UpdateStorePage updateStorePage;
    private final ReadProjects readProjects;
    private final RequestProjectReview requestProjectReview;
    private final RequestProjectRelease requestProjectRelease;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectResponse> create(
            @CurrentSubject String subject, @Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(
                response(createProject.execute(subject, request.productCode(), request.title())));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(@CurrentSubject String subject) {
        return ApiResponse.ok(readProjects.list(subject).stream().map(this::response).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> get(@PathVariable Long id, @CurrentSubject String subject) {
        return ApiResponse.ok(response(readProjects.requireOwned(id, subject)));
    }

    @PutMapping("/{id}/store-page")
    public ApiResponse<ProjectResponse> update(
            @PathVariable Long id,
            @CurrentSubject String subject,
            @Valid @RequestBody UpdateStorePageRequest request) {
        return ApiResponse.ok(
                response(
                        updateStorePage.execute(
                                id, subject, request.contentVersion(), request.page())));
    }

    @PostMapping("/{id}/review-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectReviewRequest> requestReview(
            @PathVariable Long id, @CurrentSubject String subject) {
        return ApiResponse.ok(requestProjectReview.execute(id, subject));
    }

    @GetMapping("/{id}/review-requests")
    public ApiResponse<List<ProjectReviewRequest>> reviewRequests(
            @PathVariable Long id, @CurrentSubject String subject) {
        return ApiResponse.ok(readProjects.reviewRequests(id, subject));
    }

    @PostMapping("/{id}/release-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProjectReleaseRequest> requestRelease(
            @PathVariable Long id, @CurrentSubject String subject) {
        return ApiResponse.ok(requestProjectRelease.execute(id, subject));
    }

    @GetMapping("/{id}/release-requests")
    public ApiResponse<List<ProjectReleaseRequest>> releaseRequests(
            @PathVariable Long id, @CurrentSubject String subject) {
        return ApiResponse.ok(readProjects.releaseRequests(id, subject));
    }

    private ProjectResponse response(Project p) {
        return ProjectResponse.from(
                p, readProjects.reviewRequest(p), readProjects.releaseRequest(p));
    }
}
