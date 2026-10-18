package com.stove.studio.core.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.studio.core.domain.build.Build;
import com.stove.studio.core.domain.build.BuildRepository;
import com.stove.studio.core.domain.project.Project;
import com.stove.studio.core.domain.project.ProjectRepository;
import com.stove.studio.core.domain.release.ProjectReleaseRequest;
import com.stove.studio.core.domain.release.ProjectReleaseRequestRepository;
import com.stove.studio.core.domain.review.ProjectReviewRequest;
import com.stove.studio.core.domain.review.ProjectReviewRequestRepository;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

    public List<Project> list(String subject) {
        return projects.findByOwnerSubjectOrderByIdDesc(subject);
    }

    public List<Build> builds(Long id, String subject) {
        requireOwned(id, subject);
        return builds.findByProjectIdOrderByIdDesc(id);
    }

    public List<ProjectReviewRequest> reviewRequests(Long id, String subject) {
        requireOwned(id, subject);
        return reviews.findByProjectIdOrderByIdDesc(id);
    }

    public List<ProjectReleaseRequest> releaseRequests(Long id, String subject) {
        requireOwned(id, subject);
        return releases.findByProjectIdOrderByIdDesc(id);
    }

    public ProjectReviewRequest reviewRequest(Project p) {
        return p.getCurrentReviewRequestId() == null
                ? null
                : reviews.findById(p.getCurrentReviewRequestId())
                        .filter(r -> r.getContentVersion() == p.getContentVersion())
                        .orElse(null);
    }

    public ProjectReleaseRequest releaseRequest(Project p) {
        return p.getCurrentReleaseRequestId() == null
                ? null
                : releases.findById(p.getCurrentReleaseRequestId())
                        .filter(r -> r.getContentVersion() == p.getContentVersion())
                        .orElse(null);
    }

    public ProjectSnapshot snapshot(ProjectReviewRequest request) {
        try {
            return mapper.readValue(request.getSnapshot(), ProjectSnapshot.class);
        } catch (IOException failure) {
            throw new IllegalStateException("요청 스냅샷을 읽을 수 없습니다.", failure);
        }
    }
}
