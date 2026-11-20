package com.stove.common.event;

import java.util.concurrent.CompletableFuture;

/**
 * 고른 브로커로 받을 수 없는 소비자(DB 없는 Store)를 위해 일부 토픽을 다른 브로커로도 보내는 발행 포트(결정 209).
 * 지금 구현은 Kafka adapter 하나다. 다른 adapter 는 이 포트만 알고 Kafka 를 모른다.
 *
 * <p>{@link MessagePublisher} 를 상속하지 않는다 — 상속하면 릴레이가 받을 발행 포트 후보가 둘이 된다.
 * 완료의 뜻은 {@link MessagePublisher#publish} 와 같다.
 */
public interface MirrorPublisher {
    CompletableFuture<Void> publish(OutboundMessage message);
}
