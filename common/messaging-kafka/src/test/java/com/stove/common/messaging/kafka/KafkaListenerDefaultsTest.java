package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
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

    /** 실제 기동: spring.factories 로 등록돼 돌고, 명령행 인자(앱 설정)가 이긴다(PR #223 R2). */
    @Test
    void aRealStartupRegistersTheDefaultAndLetsTheApplicationOverrideIt() {
        try (ConfigurableApplicationContext defaults = start()) {
            assertThat(defaults.getEnvironment().getProperty("spring.kafka.listener.concurrency")).isEqualTo("3");
        }
        try (ConfigurableApplicationContext overridden = start("--spring.kafka.listener.concurrency=1")) {
            assertThat(overridden.getEnvironment().getProperty("spring.kafka.listener.concurrency")).isEqualTo("1");
        }
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "spring.autoconfigure.exclude=*")
                .run(args);
    }

    /** 자동 구성 없이 환경 준비(EnvironmentPostProcessor)만 보려는 빈 구성. */
    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
