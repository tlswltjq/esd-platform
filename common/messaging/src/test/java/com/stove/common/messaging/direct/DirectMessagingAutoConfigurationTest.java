package com.stove.common.messaging.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class DirectMessagingAutoConfigurationTest {

    @Test
    void refusesToStartWhenKafkaTopicsAreRequestedWithoutAKafkaTemplate() {
        assertThatThrownBy(() -> DirectMessagingAutoConfiguration.publisher(Set.of("stove.catalog.v1"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stove.catalog.v1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsThePublisherWhenEitherNoKafkaTopicsOrATemplateIsPresent() {
        assertThat(DirectMessagingAutoConfiguration.publisher(Set.of(), null)).isNotNull();
        assertThat(DirectMessagingAutoConfiguration.publisher(Set.of("stove.catalog.v1"), mock(KafkaTemplate.class)))
                .isNotNull();
    }
}
