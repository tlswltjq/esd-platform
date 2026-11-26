package com.stove.archfixture.consumergroup.aligned;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/** 준수 — 리스너가 서비스 상수를 참조한다. */
public class AlignedListener {

    @KafkaListener(topics = "fixture.aligned.v1", groupId = AlignedService.CONSUMER_GROUP)
    public void receive(ConsumerRecord<String, String> record) {
    }
}
