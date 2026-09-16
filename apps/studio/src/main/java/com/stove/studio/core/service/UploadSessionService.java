package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.BuildStatus;
import com.stove.studio.core.domain.CreatedUploadSession;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.GameProject;
import com.stove.studio.core.domain.MultipartUploadTicket;
import com.stove.studio.core.domain.NewUploadSession;
import com.stove.studio.core.domain.StoredObjectInfo;
import com.stove.studio.core.domain.UploadSession;
import com.stove.studio.core.domain.UploadSessionRepository;
import com.stove.studio.core.domain.UploadSessionStatus;
import com.stove.studio.core.domain.UploadedPart;
import com.stove.studio.core.port.BuildStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UploadSessionService {

    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final GameBuildRepository buildRepository;
    private final UploadSessionRepository sessionRepository;
    private final GameProjectService projectService;
    private final BuildStorage buildStorage;
    private final StorageQuotaService storageQuotaService;

    public CreatedUploadSession create(Long gameId, Long workspaceId, NewUploadSession request) {
        GameProject project = projectService.requireOwned(gameId, workspaceId);
        GameBuild existing = buildRepository.findByGameIdAndIdempotencyKey(gameId, request.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            UploadSession session = sessionRepository.findByBuildId(existing.getId()).orElseThrow();
            return response(existing, session);
        }

        storageQuotaService.requireCapacity(workspaceId, request.fileSize());

        String artifactKey = UUID.randomUUID().toString();
        MultipartUploadTicket ticket = buildStorage.beginMultipart(
                project.getProductCode(), artifactKey, request.fileName(), request.fileSize());
        try {
            GameBuild build = buildRepository.save(GameBuild.uploading(gameId, request, ticket.storagePath()));
            UploadSession session = sessionRepository.save(UploadSession.open(
                    build.getId(), ticket.storageUploadId(), ticket.partSize(), ticket.parts().size(),
                    request.idempotencyKey(), Instant.now().plus(SESSION_TTL)));
            return new CreatedUploadSession(session.getId(), build.getId(), ticket.partSize(),
                    ticket.parts(), session.getExpiresAt());
        } catch (RuntimeException failure) {
            buildStorage.abortMultipart(ticket.storagePath(), ticket.storageUploadId());
            throw failure;
        }
    }

    public Long complete(Long sessionId, Long workspaceId, List<UploadedPart> parts) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "uploadSessionId=" + sessionId));
        GameBuild build = buildRepository.findById(session.getBuildId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "buildId=" + session.getBuildId()));
        projectService.requireOwned(build.getGameId(), workspaceId);

        if (session.getStatus() == UploadSessionStatus.COMPLETED) {
            return build.getId();
        }
        validateParts(session, parts);
        buildStorage.completeMultipart(build.getStoragePath(), session.getStorageUploadId(), parts);
        StoredObjectInfo object = buildStorage.head(build.getStoragePath());
        session.complete();
        build.processing(object.size());
        if (object.size() != build.getFileSize()) {
            build.fail("SIZE_MISMATCH");
        }
        return build.getId();
    }

    @Transactional(readOnly = true)
    public GameBuild requireOwnedBuild(Long buildId, Long workspaceId) {
        GameBuild build = buildRepository.findById(buildId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "buildId=" + buildId));
        projectService.requireOwned(build.getGameId(), workspaceId);
        return build;
    }

    private CreatedUploadSession response(GameBuild build, UploadSession session) {
        List<com.stove.studio.core.domain.UploadPartUrl> parts = session.getStatus() == UploadSessionStatus.OPEN
                ? buildStorage.presignParts(build.getStoragePath(), session.getStorageUploadId(), session.getPartCount())
                : List.of();
        return new CreatedUploadSession(session.getId(), build.getId(), session.getPartSize(),
                parts, session.getExpiresAt());
    }

    private void validateParts(UploadSession session, List<UploadedPart> parts) {
        if (parts.size() != session.getPartCount()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "업로드 part 개수가 일치하지 않습니다.");
        }
        for (int index = 0; index < parts.size(); index++) {
            UploadedPart part = parts.get(index);
            if (part.partNumber() != index + 1 || part.etag() == null || part.etag().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "업로드 part 정보가 올바르지 않습니다.");
            }
        }
    }
}
