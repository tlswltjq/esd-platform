package com.stove.studio.core.domain;

public record RegisteredBuildWebhook(BuildWebhookSubscription subscription, String signingSecret) {
}
