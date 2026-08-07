package com.stove.archfixture.consumergroup.missing;

import org.springframework.kafka.annotation.KafkaListener;

/** 그룹은 명시돼 있다. 없어진 것은 대조 상대인 멱등 키 쪽이다. */
public class MissingConstantListener {

    @KafkaListener(topics = "fixture.missing.v1", groupId = "missing")
    public void onEvent(String payload) {
    }
}
