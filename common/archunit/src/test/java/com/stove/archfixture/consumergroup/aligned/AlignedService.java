package com.stove.archfixture.consumergroup.aligned;

import com.stove.common.messaging.inbox.ProcessedEventGuard;

/** 준수 — Kafka 그룹과 멱등 키가 한 상수에서 나온다. */
public class AlignedService {

    public static final String CONSUMER_GROUP = "aligned";

    private final ProcessedEventGuard processedEventGuard;

    public AlignedService(ProcessedEventGuard processedEventGuard) {
        this.processedEventGuard = processedEventGuard;
    }

    public boolean handle(String eventId, String eventType) {
        return processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType);
    }
}
