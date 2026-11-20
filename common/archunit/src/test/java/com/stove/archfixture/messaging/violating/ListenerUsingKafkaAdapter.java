package com.stove.archfixture.messaging.violating;

import com.stove.common.messaging.kafka.archfixture.KafkaFixture;

/** 위반: 업무 코드가 adapter 패키지를 안다(결정 209). */
public class ListenerUsingKafkaAdapter {
    public String name(KafkaFixture kafka) {
        return kafka.toString();
    }
}
