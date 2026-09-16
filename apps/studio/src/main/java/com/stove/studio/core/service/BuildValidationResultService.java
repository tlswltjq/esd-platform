package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.BuildValidatedEvent;
import com.stove.common.event.payload.BuildValidationFailedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.GameProject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BuildValidationResultService {

    private final GameBuildRepository buildRepository;
    private final GameProjectService projectService;
    private final OutboxRecorder outboxRecorder;

    public void validated(Long buildId, String checksum) {
        GameBuild build = requireBuild(buildId);
        GameProject project = projectService.requireById(build.getGameId());
        Long duplicateOf = buildRepository.findFirstByActualChecksumAndStatusOrderByIdAsc(
                        checksum, com.stove.studio.core.domain.BuildStatus.VALIDATED)
                .map(GameBuild::getId)
                .filter(id -> !id.equals(buildId))
                .orElse(null);
        build.validated(checksum, duplicateOf);
        outboxRecorder.record(GameProjectService.AGGREGATE, project.getProductCode(),
                BuildValidatedEvent.of(build.getId(), build.getGameId(), project.getProductCode(),
                        checksum, build.getPlatform(), build.getVersion(), build.getBuildNumber(),
                        build.getCommitSha()));
    }

    public void failed(Long buildId, String failureCode) {
        GameBuild build = requireBuild(buildId);
        GameProject project = projectService.requireById(build.getGameId());
        build.fail(failureCode);
        outboxRecorder.record(GameProjectService.AGGREGATE, project.getProductCode(),
                BuildValidationFailedEvent.of(build.getId(), build.getGameId(), project.getProductCode(), failureCode));
    }

    private GameBuild requireBuild(Long buildId) {
        return buildRepository.findById(buildId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "buildId=" + buildId));
    }
}
