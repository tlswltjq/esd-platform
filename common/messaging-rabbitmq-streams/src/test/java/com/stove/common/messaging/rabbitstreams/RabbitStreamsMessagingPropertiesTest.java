package com.stove.common.messaging.rabbitstreams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.messaging.rabbitstreams.config.RabbitStreamsMessagingProperties;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RabbitStreamsMessagingPropertiesTest {

    @Test
    void 비운_값은_단일_노드_기본값과_분할_3개_보존_7일이다() {
        RabbitStreamsMessagingProperties properties = new RabbitStreamsMessagingProperties(null, null, null, null, null, null, null);

        assertThat(properties.host()).isEqualTo("localhost");
        assertThat(properties.port()).isEqualTo(5552);
        assertThat(properties.partitionsPerTopic()).isEqualTo(3);
        assertThat(properties.maxAge()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.alsoKafkaTopics()).isEmpty();
    }

    @Test
    void 분할은_하나_이상이고_보존_기간은_0보다_길다() {
        assertThatThrownBy(() -> new RabbitStreamsMessagingProperties(null, null, null, null, 0, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("partitions-per-topic");
        assertThatThrownBy(() -> new RabbitStreamsMessagingProperties(null, null, null, null, 3, Duration.ZERO, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-age");
    }

    @Test
    void 키가_없으면_빈_라우팅_키로_늘_같은_분할에_간다() {
        assertThat(RabbitStreamsTopology.routingKey(null)).isEmpty();
        assertThat(RabbitStreamsTopology.routingKey("K")).isEqualTo("K");
    }
}
