package com.stove.studio.core.service.release;

import com.stove.common.kafka.contract.payload.ProjectReleaseRequestedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.*;
import com.stove.studio.core.domain.project.*;
import com.stove.studio.core.domain.release.*;
import com.stove.studio.core.port.BuildStorage;
import com.stove.studio.core.service.project.ReadProjects;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates the creator's current review result and emits a release request. */
@Service
@RequiredArgsConstructor
@Transactional
public class RequestProjectRelease {
    private final ProjectRepository projects;
    private final ProjectReleaseRequestRepository requests;
    private final ReadProjects readProjects;
    private final OutboxRecorder outbox;
    private final BuildStorage storage;

    public ProjectReleaseRequest execute(Long id, String subject) {
        var p = projects.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        p.requireOwner(subject);
        var review = readProjects.reviewRequest(p);
        if (review == null || !review.approvedFor(p.getContentVersion()))
            throw new BusinessException(ErrorCode.CONFLICT, "현재 상세정보 버전의 심사 승인 결과가 필요합니다.");
        var current = readProjects.releaseRequest(p);
        if (current != null && !"REJECTED".equals(current.getStatus())) return current;
        var snapshot = readProjects.snapshot(review);
        if (storage.size(snapshot.storagePath()) != snapshot.fileSize())
            throw new BusinessException(ErrorCode.CONFLICT, "빌드 파일을 확인할 수 없습니다.");
        var request =
                requests.saveAndFlush(
                        ProjectReleaseRequest.create(id, review.getId(), p.getContentVersion()));
        var event = ProjectReleaseRequestedEvent.of(request.getId(), review.getId(), snapshot);
        request.requested(event.eventId());
        p.linkReleaseRequest(request.getId());
        outbox.record("Project", p.getProductCode(), event);
        return request;
    }
}
