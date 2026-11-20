package com.stove.common.messaging.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.stove.common.event.MirrorPublisher;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirectMessagingAutoConfigurationTest {

    @Test
    void refusesToStartWhenKafkaTopicsAreRequestedWithoutAMirrorPublisher() {
        assertThatThrownBy(() -> DirectMessagingAutoConfiguration.publisher(Set.of("stove.catalog.v1"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stove.catalog.v1");
    }

    @Test
    void buildsThePublisherWhenEitherNoKafkaTopicsOrAMirrorIsPresent() {
        assertThat(DirectMessagingAutoConfiguration.publisher(Set.of(), null)).isNotNull();
        assertThat(DirectMessagingAutoConfiguration.publisher(Set.of("stove.catalog.v1"), mock(MirrorPublisher.class)))
                .isNotNull();
    }
}
