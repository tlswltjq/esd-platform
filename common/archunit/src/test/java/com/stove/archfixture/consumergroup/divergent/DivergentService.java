package com.stove.archfixture.consumergroup.divergent;

import com.stove.common.messaging.inbox.ProcessedEventGuard;

/** 위반 — 멱등 키가 Kafka 그룹과 다른 값으로 갈라져 있다. */
public class DivergentService {

    public static final String CONSUMER_GROUP = "inbox-side";

    private final ProcessedEventGuard processedEventGuard;

    public DivergentService(ProcessedEventGuard processedEventGuard) {
        this.processedEventGuard = processedEventGuard;
    }

    public boolean handle(String eventId, String eventType) {
        return processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType);
    }
}
