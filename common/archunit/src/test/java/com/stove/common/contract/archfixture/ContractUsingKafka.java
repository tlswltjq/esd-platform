package com.stove.common.contract.archfixture;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/** 위반: 이벤트 계약이 브로커 타입을 안다(결정 209). */
public record ContractUsingKafka(ConsumerRecord<String, String> record) {
}
