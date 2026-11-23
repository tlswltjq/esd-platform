package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** 결정 212: Kafka 리스너 동시성 기본값은 토픽 파티션 수(3)다. 앱이 정한 값이 언제나 이긴다. */
class KafkaListenerDefaultsTest {

    @Test
    void withoutAnExplicitSettingEachPartitionGetsItsOwnListenerThread() {
        MockEnvironment environment = new MockEnvironment();

        new KafkaListenerDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.kafka.listener.concurrency")).isEqualTo("3");
    }

    @Test
    void anExplicitSettingWins() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.kafka.listener.concurrency", "1");

        new KafkaListenerDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.kafka.listener.concurrency")).isEqualTo("1");
    }
}
