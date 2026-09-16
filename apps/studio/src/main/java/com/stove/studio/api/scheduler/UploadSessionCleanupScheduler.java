package com.stove.studio.api.scheduler;

import com.stove.studio.core.service.UploadSessionCleanupService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UploadSessionCleanupScheduler {

    private final UploadSessionCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${stove.upload.cleanup-interval-ms:60000}")
    @SchedulerLock(name = "upload-session-cleanup", lockAtMostFor = "PT5M")
    public void expireOpenSessions() {
        cleanupService.expireOpenSessions();
    }
}
