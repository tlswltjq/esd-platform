package com.stove.common.contract.archfixture;

import org.springframework.kafka.core.KafkaTemplate;

/** 위반: 이벤트 계약이 리스너·발행 인프라(Spring Kafka)를 안다. */
public record ContractUsingKafkaTemplate(KafkaTemplate<String, String> template) {
}
