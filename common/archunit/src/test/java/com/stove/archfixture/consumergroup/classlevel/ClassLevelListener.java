package com.stove.archfixture.consumergroup.classlevel;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 위반 — 클래스에 {@code @KafkaListener} 를 달았다.
 *
 * <p>그룹 규칙은 메서드의 {@code @KafkaListener} 에서 {@code groupId} 를 읽는다. 클래스 수준이면 그룹 명시·Inbox 대조를
 * 모두 빠져나간다(PR #250 리뷰 R1).
 */
@KafkaListener(topics = "fixture.classlevel.v1", groupId = "class-side")
public class ClassLevelListener {

    @KafkaHandler
    public void receive(ConsumerRecord<String, String> record) {
    }
}
