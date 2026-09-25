package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.InternalTesterGrant;
import com.stove.studio.core.domain.InternalTesterGrantRepository;
import com.stove.studio.core.domain.ReleaseChannel;
import com.stove.studio.core.domain.ReleaseRepository;
import com.stove.studio.core.domain.ReleaseStatus;
import com.stove.studio.core.domain.TesterInstallation;
import com.stove.studio.core.port.BuildStorage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class InternalTesterService {
    private final InternalTesterGrantRepository grantRepository;
    private final GameProjectService projectService;
    private final GameBuildRepository buildRepository;
    private final ReleaseRepository releaseRepository;
    private final BuildStorage buildStorage;
    private final AuditLogService auditLogService;

    public InternalTesterGrant grant(Long gameId, Long workspaceId, String testerSubject,
                                     ReleaseChannel channel, String actor) {
        projectService.requireOwned(gameId, workspaceId);
        if (channel == null || channel == ReleaseChannel.LIVE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "내부 테스터는 dev/test/stage 채널만 접근할 수 있습니다.");
        }
        InternalTesterGrant grant = grantRepository.findByGameIdAndTesterSubjectAndChannel(
                        gameId, testerSubject, channel)
                .orElseGet(() -> InternalTesterGrant.grant(gameId, workspaceId, testerSubject, channel));
        grant.activate();
        InternalTesterGrant saved = grantRepository.save(grant);
        auditLogService.record(actor, "TESTER_GRANTED", "InternalTesterGrant", saved.getId(),
                "subject=" + testerSubject + ",channel=" + channel);
        return saved;
    }

    public void revoke(Long gameId, Long grantId, Long workspaceId, String actor) {
        projectService.requireOwned(gameId, workspaceId);
        InternalTesterGrant grant = grantRepository.findById(grantId)
                .filter(value -> value.getGameId().equals(gameId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "testerGrantId=" + grantId));
        grant.revoke();
        auditLogService.record(actor, "TESTER_REVOKED", "InternalTesterGrant", grantId, null);
    }

    @Transactional(readOnly = true)
    public List<InternalTesterGrant> grants(Long gameId, Long workspaceId) {
        projectService.requireOwned(gameId, workspaceId);
        return grantRepository.findByGameIdOrderByIdDesc(gameId);
    }

    @Transactional(readOnly = true)
    public TesterInstallation installation(Long buildId, ReleaseChannel channel, String testerSubject) {
        if (channel == null || channel == ReleaseChannel.LIVE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "내부 설치 채널이 필요합니다.");
        }
        GameBuild build = buildRepository.findById(buildId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "buildId=" + buildId));
        boolean released = releaseRepository.existsByBuildIdAndChannelAndStatus(
                buildId, channel, ReleaseStatus.PUBLISHED);
        boolean granted = grantRepository.existsByGameIdAndTesterSubjectAndChannelAndActiveTrue(
                build.getGameId(), testerSubject, channel);
        if (!released || !granted) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 채널의 테스트 설치 권한이 없습니다.");
        }
        return new TesterInstallation(buildId, channel, buildStorage.presignDownload(build.getStoragePath()),
                Instant.now().plus(15, ChronoUnit.MINUTES));
    }
}
