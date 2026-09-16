package com.stove.studio.api.scheduler;

import com.stove.studio.core.service.BuildRetentionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BuildRetentionScheduler {

    private final BuildRetentionService retentionService;

    @Scheduled(cron = "${stove.upload.retention-cron:0 15 3 * * *}")
    @SchedulerLock(name = "build-retention", lockAtMostFor = "PT30M")
    public void expireArtifacts() {
        retentionService.expireArtifacts();
    }
}
