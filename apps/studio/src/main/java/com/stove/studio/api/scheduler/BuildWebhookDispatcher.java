package com.stove.studio.api.scheduler;

import com.stove.studio.core.service.BuildWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stove.ci.webhook.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class BuildWebhookDispatcher {
    private final BuildWebhookService webhookService;

    @Scheduled(fixedDelayString = "${stove.ci.webhook.dispatch-interval-ms:1000}")
    public void dispatch() {
        webhookService.dispatchDue();
    }
}
