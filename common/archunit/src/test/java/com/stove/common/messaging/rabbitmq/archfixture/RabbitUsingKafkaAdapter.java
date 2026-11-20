package com.stove.common.messaging.rabbitmq.archfixture;

import com.stove.common.messaging.kafka.archfixture.KafkaFixture;

/** 위반: 한 adapter 가 다른 adapter 를 안다(결정 209). */
public class RabbitUsingKafkaAdapter {
    public String name(KafkaFixture kafka) {
        return kafka.toString();
    }
}
