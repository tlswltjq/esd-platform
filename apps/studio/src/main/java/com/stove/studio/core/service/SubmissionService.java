package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.SubmissionCreatedEvent;
import com.stove.common.event.payload.BuildVariant;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.BuildStatus;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.KoreanRatingPolicy;
import com.stove.studio.core.domain.PricingRevision;
import com.stove.studio.core.domain.PricingRevisionRepository;
import com.stove.studio.core.domain.RatingRevision;
import com.stove.studio.core.domain.RatingRevisionRepository;
import com.stove.studio.core.domain.StorePageRevision;
import com.stove.studio.core.domain.StorePageRevisionRepository;
import com.stove.studio.core.domain.StorePageRevisionStatus;
import com.stove.studio.core.domain.Submission;
import com.stove.studio.core.domain.SubmissionBuild;
import com.stove.studio.core.domain.SubmissionBuildRepository;
import com.stove.studio.core.domain.SubmissionRepository;
import com.stove.studio.core.domain.SubmissionGate;
import com.stove.studio.core.domain.SubmissionGateRepository;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
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
    private final SubmissionBuildRepository submissionBuildRepository;
    private final KoreanRatingPolicy ratingPolicy;

    public Submission submit(Long gameId, Long workspaceId, Long metadataRevisionId,
                             Long pricingRevisionId, Long ratingRevisionId, Long buildId) {
        return submit(gameId, workspaceId, metadataRevisionId, pricingRevisionId, ratingRevisionId,
                buildId, List.of());
    }

    public Submission submit(Long gameId, Long workspaceId, Long metadataRevisionId,
                             Long pricingRevisionId, Long ratingRevisionId, Long buildId,
                             List<Long> additionalBuildIds) {
        GameProject project = projectService.requireOwned(gameId, workspaceId);
        StorePageRevision metadata = storeRepository.findById(metadataRevisionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "상점 revision이 프로젝트와 다릅니다."));
        if (metadata.getStatus() != StorePageRevisionStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "발행된 상점 revision만 심의에 제출할 수 있습니다.");
        }
        PricingRevision pricing = pricingRepository.findById(pricingRevisionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "가격 revision이 프로젝트와 다릅니다."));
        RatingRevision rating = ratingRepository.findById(ratingRevisionId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "등급 revision이 프로젝트와 다릅니다."));
        ratingPolicy.requireActive(rating.getCountry(), rating.getPolicyVersion());
        List<GameBuild> builds = validatedBuilds(gameId, buildId, additionalBuildIds);
        GameBuild build = builds.get(0);
        if (project.getProductKind() == com.stove.studio.core.domain.ProductKind.DEMO
                && pricing.getPrice() != 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "DEMO는 무료여야 합니다.");
        }

        int sequence = submissionRepository.findTopByGameIdOrderBySequenceNoDesc(gameId)
                .map(value -> value.getSequenceNo() + 1).orElse(1);
        Submission submission = submissionRepository.save(Submission.create(gameId, workspaceId, sequence,
                metadataRevisionId, pricingRevisionId, ratingRevisionId, buildId));
        builds.forEach(item -> submissionBuildRepository.save(SubmissionBuild.of(submission.getId(), item.getId())));
        List.of("RATING", "STORE_PAGE", "BUILD_QA", "LEGAL", "SDK_COMPLIANCE", "COMMERCIAL")
                .forEach(type -> gateRepository.save(SubmissionGate.pending(submission.getId(), type)));

        outboxRecorder.record("Submission", submission.getId().toString(), SubmissionCreatedEvent.of(
                submission.getId(), gameId, project.getProductCode(), workspaceId,
                metadataRevisionId, pricingRevisionId, ratingRevisionId, buildId,
                metadata.getTitle(), metadata.getShortDescription(), pricing.getPrice(), pricing.getCurrency(),
                rating.getResolvedPath().name(), rating.getPolicyVersion(), rating.getCountry(),
                rating.getRecommendedRatingCode(), rating.getQuestionnaire(), build.getVersion(),
                builds.stream().map(SubmissionService::variant).toList()));
        return submission;
    }

    private List<GameBuild> validatedBuilds(Long gameId, Long buildId, List<Long> additionalBuildIds) {
        if (buildId == null || additionalBuildIds == null || additionalBuildIds.size() > 19) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "제출 빌드 목록이 올바르지 않습니다.");
        }
        List<Long> ids = new ArrayList<>();
        ids.add(buildId);
        ids.addAll(additionalBuildIds);
        if (ids.stream().anyMatch(id -> id == null) || ids.stream().distinct().count() != ids.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "중복되거나 비어 있는 빌드 ID가 있습니다.");
        }
        List<GameBuild> builds = ids.stream().map(id -> buildRepository.findById(id)
                .filter(value -> value.getGameId().equals(gameId))
                .filter(value -> value.getStatus() == BuildStatus.VALIDATED)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "검증된 동일 프로젝트 빌드만 제출할 수 있습니다. buildId=" + id))).toList();
        validateBuildSet(builds);
        return builds;
    }

    static void validateBuildSet(List<GameBuild> builds) {
        GameBuild primary = builds.get(0);
        if (primary.getDeltaFromVersion() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "기본 빌드는 전체 파일이어야 합니다.");
        }
        Set<String> targets = new HashSet<>();
        for (GameBuild item : builds) {
            if (!primary.getVersion().equals(item.getVersion())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "제출 빌드의 대상 버전이 다릅니다.");
            }
            String target = item.getPlatform() + ":" + item.getArchitecture();
            String identity = target + ":" + item.getDeltaFromVersion();
            if (!targets.add(identity)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "동일 OS·아키텍처·패치 기준이 중복됩니다.");
            }
        }
        for (GameBuild item : builds) {
            if (item.getDeltaFromVersion() != null) {
                String fullTarget = item.getPlatform() + ":" + item.getArchitecture() + ":null";
                if (!targets.contains(fullTarget)) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "델타 패치에는 동일 OS·아키텍처의 전체 빌드가 필요합니다.");
                }
            }
        }
    }

    static BuildVariant variant(GameBuild build) {
        return new BuildVariant(build.getId(), build.getPlatform(), build.getArchitecture(),
                build.getVersion(), build.getFileSize(), build.getActualChecksum(),
                build.getStoragePath(), build.getDeltaFromVersion());
    }

    public Submission requireOwned(Long submissionId, Long workspaceId) {
        return submissionRepository.findById(submissionId)
                .filter(value -> value.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "submissionId=" + submissionId));
    }
}
