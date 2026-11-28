package com.stove.common.contract.archfixture;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/** 허용: 이벤트 계약은 Kafka 레코드 타입을 안다(이벤트 메시지를 레코드에서 만든다, 결정 227). */
public record ContractUsingKafkaRecord(ConsumerRecord<String, String> record) {
}
