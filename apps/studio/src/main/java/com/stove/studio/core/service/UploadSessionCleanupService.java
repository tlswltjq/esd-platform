package com.stove.studio.core.service;

import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.UploadSession;
import com.stove.studio.core.domain.UploadSessionRepository;
import com.stove.studio.core.domain.UploadSessionStatus;
import com.stove.studio.core.port.BuildStorage;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UploadSessionCleanupService {

    private final UploadSessionRepository sessionRepository;
    private final GameBuildRepository buildRepository;
    private final BuildStorage buildStorage;

    @Transactional
    public int expireOpenSessions() {
        var sessions = sessionRepository.findTop100ByStatusAndExpiresAtBeforeOrderByIdAsc(
                UploadSessionStatus.OPEN, Instant.now());
        for (UploadSession session : sessions) {
            GameBuild build = buildRepository.findById(session.getBuildId()).orElse(null);
            if (build == null) {
                session.expire();
                continue;
            }
            buildStorage.abortMultipart(build.getStoragePath(), session.getStorageUploadId());
            session.expire();
            build.expireUpload();
        }
        return sessions.size();
    }
}
