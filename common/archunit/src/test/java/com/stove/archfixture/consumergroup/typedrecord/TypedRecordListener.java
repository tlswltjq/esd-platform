package com.stove.archfixture.consumergroup.typedrecord;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/** 위반 — 값 타입이 String 이 아니다. 격리 재투입은 문자열 본문을 넘긴다(PR #250 재리뷰 R1). */
public class TypedRecordListener {

    @KafkaListener(topics = "fixture.typedrecord.v1", groupId = "typed-side")
    public void receive(ConsumerRecord<String, Integer> record) {
    }

    /** 위반 — 와일드카드. 소거하면 String 이지만 정확히 {@code String, String} 이 아니다. */
    @KafkaListener(topics = "fixture.typedrecord.v1", groupId = "wildcard-side")
    public void receiveWildcard(ConsumerRecord<String, ? extends String> record) {
    }
}
