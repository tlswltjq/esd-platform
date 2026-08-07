package com.stove.archfixture.consumergroup.guardless;

import org.springframework.kafka.annotation.KafkaListener;

/** 준수 — 그룹은 명시하되 멱등 키는 없다. 대조 대상이 아니다. */
public class GuardlessListener {

    @KafkaListener(topics = "fixture.guardless.v1", groupId = "guardless")
    public void onEvent(String payload) {
    }
}
