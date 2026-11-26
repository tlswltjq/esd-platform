package com.stove.common.messaging.kafka.archfixture;

import com.stove.common.messaging.archfixture.CoreFixture;

/** 위반: Kafka 공통 모듈이 메시징 코어(JPA)를 안다. */
public class KafkaUsingCore {
    private final CoreFixture core = new CoreFixture();

    public CoreFixture core() {
        return core;
    }
}
