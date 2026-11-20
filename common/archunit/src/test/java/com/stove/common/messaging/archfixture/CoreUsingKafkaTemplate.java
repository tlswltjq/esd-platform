package com.stove.common.messaging.archfixture;

import org.springframework.kafka.core.KafkaTemplate;

/** 위반: 메시징 코어가 Kafka 클라이언트를 안다(결정 209). */
public class CoreUsingKafkaTemplate {
    private final KafkaTemplate<String, String> template;

    public CoreUsingKafkaTemplate(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    public KafkaTemplate<String, String> template() {
        return template;
    }
}
