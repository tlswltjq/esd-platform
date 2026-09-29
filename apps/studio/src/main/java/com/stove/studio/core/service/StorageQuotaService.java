package com.stove.studio.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.studio.core.domain.BuildStatus;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.WorkspaceRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorageQuotaService {

    private static final List<BuildStatus> ACTIVE = List.of(
            BuildStatus.UPLOADING, BuildStatus.PROCESSING, BuildStatus.VALIDATED);
    private static final List<BuildStatus> STORED = List.of(
            BuildStatus.UPLOADING, BuildStatus.PROCESSING, BuildStatus.VALIDATED, BuildStatus.FAILED);

    private final WorkspaceRepository workspaceRepository;
    private final GameBuildRepository buildRepository;
    private final long workspaceQuotaBytes;
    private final long maxArtifactBytes;
    private final long maxActiveBuilds;

    public StorageQuotaService(
            WorkspaceRepository workspaceRepository,
            GameBuildRepository buildRepository,
            @Value("${stove.upload.workspace-quota-bytes:53687091200}") long workspaceQuotaBytes,
            @Value("${stove.upload.max-artifact-bytes:21474836480}") long maxArtifactBytes,
            @Value("${stove.upload.max-active-builds:20}") long maxActiveBuilds) {
        this.workspaceRepository = workspaceRepository;
        this.buildRepository = buildRepository;
        this.workspaceQuotaBytes = workspaceQuotaBytes;
        this.maxArtifactBytes = maxArtifactBytes;
        this.maxActiveBuilds = maxActiveBuilds;
    }

    /** workspace 행 잠금으로 동시 업로드 세션 생성 시 quota 초과 예약을 직렬화한다. */
    public void requireCapacity(Long workspaceId, long requestedBytes) {
        workspaceRepository.findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "workspaceId=" + workspaceId));
        if (requestedBytes > maxArtifactBytes) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "단일 빌드 최대 용량을 초과했습니다.");
        }
        long reserved = buildRepository.sumReservedBytes(workspaceId, STORED);
        if (buildRepository.countReservedBuilds(workspaceId, ACTIVE) >= maxActiveBuilds) {
            throw new BusinessException(ErrorCode.CONFLICT, "활성 빌드 개수 제한을 초과했습니다.");
        }
        if (requestedBytes > workspaceQuotaBytes - reserved) {
            throw new BusinessException(ErrorCode.CONFLICT, "워크스페이스 저장 용량을 초과했습니다.");
        }
    }
}
