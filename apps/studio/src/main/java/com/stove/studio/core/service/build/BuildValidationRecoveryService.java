package com.stove.studio.core.service.build;

import com.stove.studio.core.domain.build.BuildStatus;
import com.stove.studio.core.domain.build.GameBuildRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BuildValidationRecoveryService {
    private final GameBuildRepository buildRepository;
    private final BuildValidationDispatcherService dispatcherService;

    public int recover() {
        var builds = buildRepository.findTop100ByStatusAndUpdatedAtBeforeOrderByIdAsc(
                BuildStatus.PROCESSING, Instant.now().minus(5, ChronoUnit.MINUTES));
        builds.forEach(build -> dispatcherService.dispatch(build.getId()));
        return builds.size();
    }
}
