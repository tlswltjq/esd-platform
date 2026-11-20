package com.stove.archfixture.messaging.violating;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/** 위반: 업무 코드가 브로커 타입을 안다(결정 209). */
public class ListenerUsingKafkaRecord {
    public String key(ConsumerRecord<String, String> record) {
        return record.key();
    }
}
