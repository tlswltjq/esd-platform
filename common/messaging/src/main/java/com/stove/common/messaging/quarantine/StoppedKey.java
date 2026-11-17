package com.stove.common.messaging.quarantine;

import java.time.Instant;

/** 멈춘 키 하나. 운영 API 가 목록으로 보여 준다. */
public record StoppedKey(String consumerGroup, String topic, String partitionKey, long held,
                         Long firstId, Instant firstQuarantinedAt) {
}
