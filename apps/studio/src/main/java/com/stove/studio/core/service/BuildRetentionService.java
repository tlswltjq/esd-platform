package com.stove.studio.core.service;

import com.stove.studio.core.domain.BuildStatus;
import com.stove.studio.core.domain.GameBuild;
import com.stove.studio.core.domain.GameBuildRepository;
import com.stove.studio.core.domain.SubmissionRepository;
import com.stove.studio.core.port.BuildStorage;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BuildRetentionService {

    private final GameBuildRepository buildRepository;
    private final SubmissionRepository submissionRepository;
    private final BuildStorage buildStorage;
    private final Duration failedRetention;
    private final Duration unsubmittedRetention;

    public BuildRetentionService(GameBuildRepository buildRepository,
                                 SubmissionRepository submissionRepository,
                                 BuildStorage buildStorage,
                                 @Value("${stove.upload.failed-retention:7d}") Duration failedRetention,
                                 @Value("${stove.upload.unsubmitted-retention:30d}") Duration unsubmittedRetention) {
        this.buildRepository = buildRepository;
        this.submissionRepository = submissionRepository;
        this.buildStorage = buildStorage;
        this.failedRetention = failedRetention;
        this.unsubmittedRetention = unsubmittedRetention;
    }

    public int expireArtifacts() {
        int expired = retire(buildRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByIdAsc(
                BuildStatus.FAILED, Instant.now().minus(failedRetention)), false);
        expired += retire(buildRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByIdAsc(
                BuildStatus.VALIDATED, Instant.now().minus(unsubmittedRetention)), true);
        return expired;
    }

    private int retire(java.util.List<GameBuild> builds, boolean protectSubmitted) {
        int count = 0;
        for (GameBuild build : builds) {
            if (protectSubmitted && submissionRepository.existsByBuildId(build.getId())) {
                continue;
            }
            buildStorage.delete(build.getStoragePath());
            build.retire();
            count++;
        }
        return count;
    }
}
