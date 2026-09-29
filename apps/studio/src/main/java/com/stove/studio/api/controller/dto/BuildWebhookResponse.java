package com.stove.studio.api.controller.dto;

import com.stove.studio.core.domain.BuildWebhookSubscription;
import com.stove.studio.core.domain.RegisteredBuildWebhook;

public record BuildWebhookResponse(Long subscriptionId, Long gameId, String endpointUrl,
                                   boolean active, String signingSecret) {
    public static BuildWebhookResponse from(BuildWebhookSubscription value) {
        return new BuildWebhookResponse(value.getId(), value.getGameId(), value.getEndpointUrl(),
                value.isActive(), null);
    }

    public static BuildWebhookResponse from(RegisteredBuildWebhook value) {
        BuildWebhookSubscription subscription = value.subscription();
        return new BuildWebhookResponse(subscription.getId(), subscription.getGameId(),
                subscription.getEndpointUrl(), subscription.isActive(), value.signingSecret());
    }
}
