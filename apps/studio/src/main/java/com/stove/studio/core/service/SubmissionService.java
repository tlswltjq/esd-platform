package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.SubmissionCreatedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.BuildStatus;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.PricingRevision;
import com.stove.studio.core.domain.PricingRevisionRepository;
import com.stove.studio.core.domain.RatingRevision;
import com.stove.studio.core.domain.RatingRevisionRepository;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionRepository;
import com.stove.studio.core.domain.Submission;
import com.stove.studio.core.domain.SubmissionRepository;
import com.stove.studio.core.domain.SubmissionGate;
import com.stove.studio.core.domain.SubmissionGateRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final StorePageRevisionRepository storeRepository;
    private final PricingRevisionRepository pricingRepository;
    private final RatingRevisionRepository ratingRepository;
    private final GameBuildRepository buildRepository;
    private final GameProjectService projectService;
    private final OutboxRecorder outboxRecorder;
    private final SubmissionGateRepository gateRepository;

    public Submission submit(Long gameId, Long workspaceId, Long metadataRevisionId,
                             Long pricingRevisionId, Long ratingRevisionId, Long buildId) {
        GameProject project = projectService.requireOwned(gameId, workspaceId);
        StorePageRevision metadata = storeRepository.findById(metadataRevisionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "상점 revision이 프로젝트와 다릅니다."));
        PricingRevision pricing = pricingRepository.findById(pricingRevisionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "가격 revision이 프로젝트와 다릅니다."));
        RatingRevision rating = ratingRepository.findById(ratingRevisionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "등급 revision이 프로젝트와 다릅니다."));
        GameBuild build = buildRepository.findById(buildId)
                .filter(value -> value.getGameId().equals(gameId))
                .filter(value -> value.getStatus() == BuildStatus.VALIDATED)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "검증된 빌드만 제출할 수 있습니다."));

        int sequence = submissionRepository.findTopByGameIdOrderBySequenceNoDesc(gameId)
                .map(value -> value.getSequenceNo() + 1).orElse(1);
        Submission submission = submissionRepository.save(Submission.create(gameId, workspaceId, sequence,
                metadataRevisionId, pricingRevisionId, ratingRevisionId, buildId));
        List.of("RATING", "STORE_PAGE", "BUILD_QA")
                .forEach(type -> gateRepository.save(SubmissionGate.pending(submission.getId(), type)));

        outboxRecorder.record("Submission", submission.getId().toString(), SubmissionCreatedEvent.of(
                submission.getId(), gameId, project.getProductCode(), workspaceId,
                metadataRevisionId, pricingRevisionId, ratingRevisionId, buildId,
                metadata.getTitle(), metadata.getShortDescription(), pricing.getPrice(), pricing.getCurrency(),
                rating.getResolvedPath().name(), rating.getPolicyVersion(), build.getVersion()));
        return submission;
    }

    public Submission requireOwned(Long submissionId, Long workspaceId) {
        return submissionRepository.findById(submissionId)
                .filter(value -> value.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "submissionId=" + submissionId));
    }
}
