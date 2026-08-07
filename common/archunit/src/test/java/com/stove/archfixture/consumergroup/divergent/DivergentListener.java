package com.stove.archfixture.consumergroup.divergent;

import org.springframework.kafka.annotation.KafkaListener;

/** 위반 — 리터럴로 되돌아가 Kafka 그룹이 멱등 키와 갈라졌다. */
public class DivergentListener {

    @KafkaListener(topics = "fixture.divergent.v1", groupId = "kafka-side")
    public void onEvent(String payload) {
    }
}
