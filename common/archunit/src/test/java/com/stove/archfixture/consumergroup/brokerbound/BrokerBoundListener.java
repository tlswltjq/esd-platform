package com.stove.archfixture.consumergroup.brokerbound;

import org.springframework.kafka.annotation.KafkaListener;

/** 위반 — 브로커 리스너를 직접 달았다. 브로커를 바꿀 때 이 리스너만 따라오지 않는다. */
public class BrokerBoundListener {

    @KafkaListener(topics = "fixture.brokerbound.v1", groupId = "brokerbound")
    public void onEvent(String payload) {
    }
}
