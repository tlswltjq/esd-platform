package com.stove.studio.api.scheduler;

import com.stove.studio.core.service.BuildValidationRecoveryService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 프로세스 재시작·executor 거절로 PROCESSING에 남은 빌드를 다시 검증한다. */
@Component
@RequiredArgsConstructor
public class BuildValidationRecoveryScheduler {
    private final BuildValidationRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${stove.upload.validation-recovery-interval-ms:60000}")
    @SchedulerLock(name = "build-validation-recovery", lockAtMostFor = "PT5M")
    public void recover() {
        recoveryService.recover();
    }
}
