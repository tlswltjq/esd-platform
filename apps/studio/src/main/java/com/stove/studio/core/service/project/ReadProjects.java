package com.stove.studio.core.service.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import com.stove.common.web.error.*;
import com.stove.studio.core.domain.build.*;
import com.stove.studio.core.domain.project.*;
import com.stove.studio.core.domain.release.*;
import com.stove.studio.core.domain.review.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("요청 스냅샷을 읽을 수 없습니다.", failure);
        }
    }
}
