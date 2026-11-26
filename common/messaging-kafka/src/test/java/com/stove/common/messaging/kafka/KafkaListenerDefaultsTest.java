package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
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

    /** 실제 Kafka 자동 구성: 기본값과 앱 설정이 Boot 리스너 팩토리가 만드는 컨테이너 동시성에 반영된다(PR #223 R6). 브로커는 붙지 않는다. */
    @Test
    void theValueReachesTheContainersBootsListenerFactoryCreates() {
        try (ConfigurableApplicationContext defaults = startWithKafka()) {
            assertThat(listenerConcurrency(defaults)).isEqualTo(3);
        }
        try (ConfigurableApplicationContext overridden = startWithKafka("--spring.kafka.listener.concurrency=1")) {
            assertThat(listenerConcurrency(overridden)).isEqualTo(1);
        }
    }

    /** 재시도 선택이 다른 리스너의 팩토리({@link ListenerContainerFactories})도 같은 값을 받는다(PR #223 R9). */
    @Test
    void theValueReachesTheSubscriptionFactoryToo() {
        try (ConfigurableApplicationContext defaults = startWithKafka()) {
            assertThat(subscriptionConcurrency(defaults)).isEqualTo(3);
        }
        try (ConfigurableApplicationContext overridden = startWithKafka("--spring.kafka.listener.concurrency=1")) {
            assertThat(subscriptionConcurrency(overridden)).isEqualTo(1);
        }
    }

    private static int listenerConcurrency(ConfigurableApplicationContext context) {
        ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                context.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
        return factory.createContainer("defaults-probe").getConcurrency();
    }

    @SuppressWarnings("unchecked")
    private static int subscriptionConcurrency(ConfigurableApplicationContext context) {
        // Boot 가 등록하는 소비자 팩토리는 와일드카드 타입이라 운영 조립(KafkaConsumerAutoConfiguration)처럼 좁힌다.
        ConsumerFactory<Object, Object> consumers = context.getBean(ConsumerFactory.class);
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                context.getBean(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ListenerContainerFactories factories = new ListenerContainerFactories(() -> configurer, () -> consumers,
                (record, exception) -> { }, null);
        return factories.noRetryFor(IllegalArgumentException.class).createContainer("defaults-probe").getConcurrency();
    }

    private static ConfigurableApplicationContext startWithKafka(String... args) {
        return new SpringApplicationBuilder(WithKafka.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off").run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class WithKafka {
    }
}
