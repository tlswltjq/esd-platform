package com.stove.archfixture.consumergroup.repeated;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 위반 — 한 메서드에 {@code @KafkaListener} 를 둘 달았다. 컴파일러가 {@code @KafkaListeners} 로 감싸 바이트코드에는
 * {@code @KafkaListener} 가 보이지 않는다(PR #250 리뷰 R1).
 */
public class RepeatedListener {

    @KafkaListener(topics = "fixture.repeated.v1", groupId = "first-side")
    @KafkaListener(topics = "fixture.repeated.v1", groupId = "second-side")
    public void receive(ConsumerRecord<String, String> record) {
    }
}
