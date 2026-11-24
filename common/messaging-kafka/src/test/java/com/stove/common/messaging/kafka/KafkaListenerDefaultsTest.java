package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import com.stove.common.event.KeyQuarantine;
import java.lang.annotation.Annotation;
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

    /** 구독 전용 팩토리({@link KafkaEventSubscriptions#factoryFor}, 격리 있는 앱의 모든 구독)도 같은 값을 받는다(PR #223 R9). */
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
        // Boot 가 등록하는 소비자 팩토리는 와일드카드 타입이라 운영 조립(KafkaSubscriptionAutoConfiguration)처럼 좁힌다.
        ConsumerFactory<Object, Object> consumers = context.getBean(ConsumerFactory.class);
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                context.getBean(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        KafkaEventSubscriptions subscriptions = new KafkaEventSubscriptions(context, () -> configurer, () -> consumers,
                () -> null, NoQuarantine::new);
        return subscriptions.factoryFor(declaration(), new NoQuarantine()).createContainer("defaults-probe").getConcurrency();
    }

    private static EventSubscriber declaration() {
        return new EventSubscriber() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return EventSubscriber.class;
            }

            @Override
            public String[] topics() {
                return new String[]{"defaults-probe"};
            }

            @Override
            public String group() {
                return "defaults-probe";
            }

            @Override
            @SuppressWarnings("unchecked")
            public Class<? extends RuntimeException>[] noRetryFor() {
                return (Class<? extends RuntimeException>[]) new Class<?>[0];
            }

            @Override
            public boolean oneAtATime() {
                return false;
            }


            @Override

            public boolean retryUntilSuccess() {

                return false;

            }
        };
    }

    /** 격리를 쓰는 구독의 팩토리를 만들기 위한 자리표시. 이 테스트에서는 메시지를 받지 않는다. */
    static final class NoQuarantine implements KeyQuarantine {
        @Override
        public boolean stopped(String consumerGroup, String topic, String key) {
            return false;
        }

        @Override
        public void quarantine(String consumerGroup, InboundMessage message, Throwable failure) {
        }
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
