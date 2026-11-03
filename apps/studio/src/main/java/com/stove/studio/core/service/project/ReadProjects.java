package com.stove.studio.core.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.project.ProjectView;
import com.stove.studio.core.domain.project.StudioPage;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseRequestRepository;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequestRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadProjects {
    private final ProjectRepository projects;
    private final BuildRepository builds;
    private final ProjectReviewRequestRepository reviews;
    private final ProjectReleaseRequestRepository releases;
    private final ObjectMapper mapper;

    public Project requireOwned(Long id, String subject) {
        Project p =
                projects.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        p.requireOwner(subject);
        return p;
    }

    public Page<ProjectView> list(String subject, StudioPage input) {
        Page<Project> page = projects.findByOwnerSubjectOrderByIdDesc(subject, request(input));
        if (page.isEmpty()) return page.map(project -> new ProjectView(project, null, null, null, null));
        List<Long> ids = page.stream().map(Project::getId).toList();
        List<Long> currentReviews = page.stream().map(Project::getCurrentReviewRequestId).filter(Objects::nonNull).toList();
        List<Long> currentReleases = page.stream().map(Project::getCurrentReleaseRequestId).filter(Objects::nonNull).toList();
        List<ProjectReviewRequest> reviewRows = reviews.findForProjects(ids, currentReviews);
        List<ProjectReleaseRequest> releaseRows = releases.findForProjects(ids, currentReleases);
        Map<Long, ProjectReviewRequest> byReview = reviewRows.stream().collect(Collectors.toMap(ProjectReviewRequest::getId, Function.identity()));
        Map<Long, ProjectReleaseRequest> byRelease = releaseRows.stream().collect(Collectors.toMap(ProjectReleaseRequest::getId, Function.identity()));
        Map<Long, ProjectReviewRequest> activeReviews = reviewRows.stream().filter(row -> "REQUESTED".equals(row.getStatus()))
                .collect(Collectors.toMap(ProjectReviewRequest::getProjectId, Function.identity()));
        Map<Long, ProjectReleaseRequest> activeReleases = releaseRows.stream().filter(row -> "REQUESTED".equals(row.getStatus()))
                .collect(Collectors.toMap(ProjectReleaseRequest::getProjectId, Function.identity()));
        return page.map(project -> new ProjectView(project,
                Optional.ofNullable(byReview.get(project.getCurrentReviewRequestId()))
                        .filter(row -> row.getProjectId().equals(project.getId()) && row.getContentVersion() == project.getContentVersion()).orElse(null),
                Optional.ofNullable(byRelease.get(project.getCurrentReleaseRequestId()))
                        .filter(row -> row.getProjectId().equals(project.getId()) && row.getContentVersion() == project.getContentVersion()).orElse(null),
                activeReviews.get(project.getId()), activeReleases.get(project.getId())));
    }

    public Page<Build> builds(Long id, String subject, StudioPage input) {
        requireOwned(id, subject);
        return builds.findByProjectIdOrderByIdDesc(id, request(input));
    }

    public Page<ProjectReviewRequest> reviewRequests(Long id, String subject, StudioPage input) {
        requireOwned(id, subject);
        return reviews.findByProjectIdOrderByIdDesc(id, request(input));
    }

    public Page<ProjectReleaseRequest> releaseRequests(Long id, String subject, StudioPage input) {
        requireOwned(id, subject);
        return releases.findByProjectIdOrderByIdDesc(id, request(input));
    }

    private PageRequest request(StudioPage input) { return PageRequest.of(input.page(), input.size()); }

    public ProjectReviewRequest reviewRequest(Project p) {
        return p.getCurrentReviewRequestId() == null
                ? null
                : reviews.findById(p.getCurrentReviewRequestId())
                        .filter(r -> r.getContentVersion() == p.getContentVersion())
                        .orElse(null);
    }

    public ProjectReviewRequest activeReviewRequest(Project project) {
        return reviews.findFirstByProjectIdAndStatusOrderByIdAsc(project.getId(), "REQUESTED").orElse(null);
    }

    public ProjectReleaseRequest releaseRequest(Project p) {
        return p.getCurrentReleaseRequestId() == null
                ? null
                : releases.findById(p.getCurrentReleaseRequestId())
                        .filter(r -> r.getContentVersion() == p.getContentVersion())
                        .orElse(null);
    }

    public ProjectReleaseRequest activeReleaseRequest(Project project) {
        return releases.findFirstByProjectIdAndStatusOrderByIdAsc(project.getId(), "REQUESTED").orElse(null);
    }

    public ProjectSnapshot snapshot(ProjectReviewRequest request) {
        try {
            return mapper.readValue(request.getSnapshot(), ProjectSnapshot.class);
        } catch (IOException failure) {
            throw new IllegalStateException("요청 스냅샷을 읽을 수 없습니다.", failure);
        }
    }
}
