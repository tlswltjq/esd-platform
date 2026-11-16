package com.stove.common.messaging.outbox;

/** 같은 키의 미발행({@code SENT}가 아닌) Outbox 행 하나의 위치. {@link KeyOrderGuard}가 쓴다. */
public record OutboxKeyPosition(String partitionKey, Long id) {
}
