package com.stove.common.messaging.delivery;

import com.stove.common.event.MessagePublisher;
import com.stove.common.event.MirrorPublisher;
import com.stove.common.event.OutboundMessage;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 고른 adapter 로 발행하면서, 그 adapter 로 받을 수 없는 소비자(DB 없는 Store)를 위해 일부 토픽을 {@link MirrorPublisher}(지금은 Kafka)로도 보낸다.
 * 그 토픽은 두 곳 모두 성공해야 성공이다. 그 토픽이 있는데 미러 발행기가 없으면 기동을 멈춘다(PR #210 R3).
 * 코어는 미러가 어느 브로커인지 모른다(결정 209).
 */
public final class MirroredTopics {

    private MirroredTopics() {
    }

    /**
     * @param property 오류 메시지에 쓸 설정 이름(예: {@code stove.messaging.rabbitmq.also-kafka-topics})
     */
    public static MessagePublisher wrap(MessagePublisher primary, Set<String> mirroredTopics,
                                        MirrorPublisher mirror, String property) {
        if (mirroredTopics.isEmpty()) {
            return primary;
        }
        requireMirror(mirroredTopics, mirror, property);
        return (OutboundMessage message) -> mirroredTopics.contains(message.topic())
                ? CompletableFuture.allOf(primary.publish(message), mirror.publish(message))
                : primary.publish(message);
    }

    /** 미러할 토픽이 있는데 미러 발행기가 없으면 멈춘다 — 조용히 SENT 로만 남기지 않는다. */
    public static void requireMirror(Set<String> mirroredTopics, MirrorPublisher mirror, String property) {
        if (!mirroredTopics.isEmpty() && mirror == null) {
            throw new IllegalStateException(property + "=" + mirroredTopics
                    + " 인데 미러 발행기(MirrorPublisher, common:messaging-kafka)가 없다");
        }
    }
}
