package com.stove.common.messaging.direct;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.OutboundMessage;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 브로커 없음 기준선의 발행 포트. Outbox 행 자체가 메시지라 보낼 곳이 없다 — 릴레이는 행을 바로 {@code SENT} 로 표시하고,
 * 소비자가 그 행을 직접 읽는다({@link DirectSubscriptions}).
 *
 * <p>예외: 직접 읽을 수 없는 소비자(DB 없는 Store)가 있는 토픽은 {@code fallback}(Kafka)으로도 보낸다.
 */
public class DirectMessagePublisher implements MessagePublisher {

    private final Set<String> alsoFallbackTopics;
    private final MessagePublisher fallback;

    public DirectMessagePublisher(Set<String> alsoFallbackTopics, MessagePublisher fallback) {
        this.alsoFallbackTopics = alsoFallbackTopics;
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<Void> publish(OutboundMessage message) {
        if (fallback != null && alsoFallbackTopics.contains(message.topic())) {
            return fallback.publish(message);
        }
        return CompletableFuture.completedFuture(null);
    }
}
