package com.stove.archfixture.consumergroup.ackparam;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/** 위반 — 수동 ack 를 받는다. 격리 재투입은 ack 를 넘기지 못한다(PR #250 리뷰 B2 R2). */
public class AckParamListener {

    @KafkaListener(topics = "fixture.ackparam.v1", groupId = "ack-side")
    public void receive(ConsumerRecord<String, String> record, Acknowledgment ack) {
    }
}
