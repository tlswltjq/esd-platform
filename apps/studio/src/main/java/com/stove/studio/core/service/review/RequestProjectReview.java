package com.stove.studio.core.service.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.contract.payload.*;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.*;
import com.stove.studio.core.domain.build.*;
import com.stove.studio.core.domain.project.*;
import com.stove.studio.core.domain.review.*;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.project.ReadProjects;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RequestProjectReview {
    private final ProjectRepository projects;
    private final BuildRepository builds;
    private final ProjectReviewRequestRepository reviews;
    private final OutboxRecorder outbox;
    private final ObjectMapper mapper;
    private final BuildStorage storage;
    private final ReadProjects readProjects;

    public ProjectReviewRequest execute(Long id, String subject) {
        Project p = projects.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        p.requireOwner(subject);
        var current = readProjects.reviewRequest(p);
        if (current != null && !"REJECTED".equals(current.getStatus())) return current;
        p.requireReady();
        Build b =
                builds.findById(p.getCurrentBuildId())
                        .filter(item -> item.getProjectId().equals(id))
                        .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        if (storage.size(b.getStoragePath()) != b.getFileSize())
            throw new BusinessException(ErrorCode.CONFLICT, "빌드 파일을 확인할 수 없습니다.");
        ProjectSnapshot snapshot = snapshotFrom(p, b);
        ProjectReviewRequest review =
                reviews.saveAndFlush(
                        ProjectReviewRequest.create(
                                id, p.getContentVersion(), b.getId(), json(snapshot)));
        p.linkReviewRequest(review.getId());
        outbox.record(
                "Project",
                p.getProductCode(),
                ProjectReviewRequestedEvent.of(review.getId(), snapshot));
        return review;
    }

    private static ProjectSnapshot snapshotFrom(Project project, Build build) {
        return new ProjectSnapshot(
                project.getId(),
                project.getProductCode(),
                project.getOwnerSubject(),
                project.getContentVersion(),
                project.getTitle(),
                project.getShortDescription(),
                project.getThumbnailUrl(),
                project.getVideoUrl(),
                project.getContent(),
                project.getPrice(),
                "KRW",
                build.getId(),
                build.getVersion(),
                build.getFileSize(),
                build.getChecksum(),
                build.getStoragePath());
    }

    private String json(ProjectSnapshot value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
