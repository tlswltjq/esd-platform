package com.stove.studio.api.scheduler;

import com.stove.studio.core.service.ReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReleasePublisher {
    private final ReleaseService releaseService;

    @Scheduled(fixedDelayString = "${stove.release.publish-interval-ms:30000}")
    @SchedulerLock(name = "studio-publish-releases", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void publishDue() {
        int count = releaseService.publishDue();
        if (count > 0) log.info("예약 릴리스 발행 {}건", count);
    }
}
