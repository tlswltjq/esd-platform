package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;

/**
 * 리스너가 {@code @KafkaListener(containerFactory)} 로 고르는 팩토리가 재시도·격리·가져오는 건수를 운영과 같게 거는지 본다(결정 227).
 * 브로커 없이 컨테이너를 만들기만 한다({@code auto-startup=false}).
 */
class ListenerContainerFactoriesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaConsumerAutoConfiguration.class))
            .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false");

    @AfterEach
    void forgetConsumerThread() {
        KafkaUtils.clearConsumerGroupId();
    }

    @Test
    void aPlainListenerUsesBootsFactoryWithTheSharedErrorHandler() {
        runner.withBean(PlainListener.class).run(context -> {
            MessageListenerContainer container = container(context.getBean(KafkaListenerEndpointRegistry.class), "plain");
            assertThat(container.getGroupId()).isEqualTo("fixture-plain");
            assertThat(container.getContainerProperties().getTopics()).containsExactly("fixture.a.v1", "fixture.b.v1");
            // 특별한 선택이 없으면 공용 오류 처리기(재시도 뒤 격리처)를 그대로 쓴다.
            assertThat(errorHandler(container)).isSameAs(context.getBean("stoveKafkaErrorHandler"));
            assertThat(interceptor(container)).as("격리 없는 앱(DLT)은 멈춘 키를 거르지 않는다").isNull();
        });
    }

    @Test
    void withAQuarantineBootsFactoryAlsoFiltersStoppedKeys() {
        runner.withBean(PlainListener.class).withBean(KeyQuarantine.class, RecordingQuarantine::new).run(context -> {
            MessageListenerContainer container = container(context.getBean(KafkaListenerEndpointRegistry.class), "plain");
            assertThat(interceptor(container)).isSameAs(context.getBean(StoppedKeyInterceptor.class));
            assertThat(errorHandler(container)).isSameAs(context.getBean("stoveKafkaErrorHandler"));
        });
    }

    @Test
    void factoriesBuiltInConfigGetTheirOwnErrorHandlerAndTheApplicationContext() {
        runner.withUserConfiguration(Factories.class).withBean(RejectingListener.class).run(context -> {
            AbstractMessageListenerContainer<?, ?> container = (AbstractMessageListenerContainer<?, ?>)
                    container(context.getBean(KafkaListenerEndpointRegistry.class), "rejecting");
            assertThat(container.getCommonErrorHandler()).isNotSameAs(context.getBean("stoveKafkaErrorHandler"));
            // 컨테이너는 시작할 때 컨텍스트에서 ObservationRegistry 를 찾는다. 빠지면 이 리스너만 소비 관측이 꺼진다(PR #205 R1).
            assertThat(container.getApplicationEventPublisher()).isSameAs(context.getSourceApplicationContext());
        });
    }

    @Test
    void oneAtATimeLimitsEachPollToOneRecord() {
        runner.withUserConfiguration(Factories.class).withBean(SlowListener.class).withBean(PlainListener.class).run(context -> {
            KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
            assertThat(container(registry, "slow").getContainerProperties().getKafkaConsumerProperties())
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
            assertThat(container(registry, "plain").getContainerProperties().getKafkaConsumerProperties()
                    .getProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isNull();
            assertThat(((AbstractMessageListenerContainer<?, ?>) container(registry, "slow")).getCommonErrorHandler())
                    .as("격리 오류 처리기가 붙는다").isNotNull();
        });
    }

    @Test
    void noRetryExceptionsGoStraightToTheRecoverer() {
        List<ConsumerRecord<?, ?>> quarantined = new ArrayList<>();
        CommonErrorHandler handler = DeadLetterErrorHandlers.create((record, exception) -> quarantined.add(record), Rejected.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("fixture.a.v1", 0, 0L, "K", "{}");

        // 첫 실패에서 바로 회복(격리)했다 = 재시도하지 않았다.
        boolean recovered = handler.handleOne(new Rejected(), record, mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isTrue();
        assertThat(quarantined).containsExactly(record);
    }

    @Test
    void retryUntilSuccessNeverStopsRetryingButStillQuarantinesPermanentFailures() {
        // 결정 226: 일시 실패는 성공할 때까지 제자리 재시도한다. 대기는 8초를 넘지 않는다.
        BackOffExecution execution = ConsumerRetryPolicy.untilSuccess().start();
        long last = 0;
        for (int attempt = 0; attempt < 1_000; attempt++) {
            last = execution.nextBackOff();
            assertThat(last).isNotEqualTo(BackOffExecution.STOP).isLessThanOrEqualTo(8_000L);
        }
        assertThat(last).isEqualTo(8_000L);
        runner.withUserConfiguration(Factories.class).withBean(PersistentListener.class).run(context -> {
            MessageListenerContainer container = container(context.getBean(KafkaListenerEndpointRegistry.class), "persistent");
            assertThat(errorHandler(container)).isNotSameAs(context.getBean("stoveKafkaErrorHandler"));
        });
        // 영구 실패(noRetryFor)는 성공까지 재시도에서도 바로 격리(DLT)로 간다.
        List<ConsumerRecord<?, ?>> quarantined = new ArrayList<>();
        CommonErrorHandler handler = DeadLetterErrorHandlers.create(
                (record, exception) -> quarantined.add(record), ConsumerRetryPolicy.untilSuccess(), Rejected.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("fixture.a.v1", 0, 0L, "K", "{}");
        assertThat(handler.handleOne(new Rejected(), record, mock(Consumer.class), mock(MessageListenerContainer.class))).isTrue();
        assertThat(quarantined).containsExactly(record);
    }

    @Test
    void exhaustedRetriesGoToTheConsumerDatabaseOfTheConsumerThreadsGroup() {
        RecordingQuarantine quarantine = new RecordingQuarantine();
        IllegalStateException failure = new IllegalStateException("boom");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("fixture.a.v1", 2, 7L, "ORD-1", "{}");
        KafkaUtils.setConsumerGroupId("fixture-plain");

        ListenerContainerFactories.recoverer(null, null, quarantine).accept(record, failure);

        assertThat(quarantine.groups).containsExactly("fixture-plain");
        assertThat(quarantine.held).containsExactly(record);
        assertThat(quarantine.failures).containsExactly(failure);
    }

    @Test
    void outsideAConsumerThreadNothingIsQuarantinedWithoutAGroup() {
        RecordingQuarantine quarantine = new RecordingQuarantine();

        assertThatThrownBy(() -> ListenerContainerFactories.recoverer(null, null, quarantine)
                .accept(new ConsumerRecord<>("fixture.a.v1", 0, 0L, "K", "{}"), new IllegalStateException("boom")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(quarantine.held).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStoppedKeyIsHeldBehindTheQuarantineWithoutReachingTheListener() {
        // 결정 155: 앞 레코드가 격리된 키는 뒤 레코드도 처리하지 않고 같은 보관함 뒤에 쌓는다.
        RecordingQuarantine quarantine = new RecordingQuarantine();
        quarantine.stoppedKeys.add("ORD-1");
        StoppedKeyInterceptor interceptor = new StoppedKeyInterceptor(quarantine);
        ConsumerRecord<Object, Object> stopped = new ConsumerRecord<>("fixture.a.v1", 0, 1L, "ORD-1", "{}");
        ConsumerRecord<Object, Object> flowing = new ConsumerRecord<>("fixture.a.v1", 0, 2L, "ORD-2", "{}");
        KafkaUtils.setConsumerGroupId("fixture-plain");

        assertThat(interceptor.intercept(stopped, mock(Consumer.class))).as("리스너를 건너뛴다").isNull();
        assertThat(interceptor.intercept(flowing, mock(Consumer.class))).isSameAs(flowing);

        assertThat(quarantine.groups).containsExactly("fixture-plain");
        assertThat(quarantine.held).extracting(ConsumerRecord::key).containsExactly("ORD-1");
        assertThat(quarantine.failures).containsExactly((Throwable) null);
    }

    @Test
    void aStoppedKeyThatCannotBeRecordedThrowsSoTheOffsetIsNotCommitted() {
        RecordingQuarantine quarantine = new RecordingQuarantine();
        quarantine.stoppedKeys.add("ORD-1");
        quarantine.broken = true;
        KafkaUtils.setConsumerGroupId("fixture-plain");

        assertThatThrownBy(() -> new StoppedKeyInterceptor(quarantine)
                .intercept(new ConsumerRecord<>("fixture.a.v1", 0, 1L, "ORD-1", "{}"), mock(Consumer.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void kafkaTransactionsAndTheStoppedKeyInterceptorDoNotMix() {
        // 트랜잭션이 있으면 인터셉터가 오류 처리 밖에서 돌아 보관 실패가 재시도되지 않는다(StoppedKeyInterceptor 설명).
        runner.withBean(KeyQuarantine.class, RecordingQuarantine::new)
                .withBean(KafkaTransactionManager.class, () -> mock(KafkaTransactionManager.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("Kafka 트랜잭션");
                });
    }

    private static MessageListenerContainer container(KafkaListenerEndpointRegistry registry, String id) {
        MessageListenerContainer container = registry.getListenerContainer(id);
        assertThat(container).as(id).isNotNull();
        return container;
    }

    private static CommonErrorHandler errorHandler(MessageListenerContainer container) {
        return ((AbstractMessageListenerContainer<?, ?>) container).getCommonErrorHandler();
    }

    private static RecordInterceptor<?, ?> interceptor(MessageListenerContainer container) {
        return (RecordInterceptor<?, ?>) ReflectionTestUtils.invokeMethod(container, "getRecordInterceptor");
    }

    static class RecordingQuarantine implements KeyQuarantine {
        final List<String> stoppedKeys = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
        final List<ConsumerRecord<String, String>> held = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        volatile boolean broken;

        @Override
        public boolean stopped(String consumerGroup, String topic, String key) {
            return stoppedKeys.contains(key);
        }

        @Override
        public void quarantine(String consumerGroup, ConsumerRecord<String, String> record, Throwable failure) {
            if (broken) {
                throw new IllegalStateException("격리 DB 잠깐 안 됨");
            }
            groups.add(consumerGroup);
            held.add(record);
            failures.add(failure);
        }
    }

    /** 앱 config 가 하는 조립: 재시도 선택이 다른 리스너의 팩토리 빈. */
    @Configuration(proxyBeanMethods = false)
    static class Factories {
        @Bean
        ConcurrentKafkaListenerContainerFactory<Object, Object> rejectingFactory(ListenerContainerFactories factories) {
            return factories.noRetryFor(Rejected.class);
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<Object, Object> slowFactory(ListenerContainerFactories factories) {
            return factories.oneAtATime();
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<Object, Object> persistentFactory(ListenerContainerFactories factories) {
            return factories.retryUntilSuccess(Rejected.class);
        }
    }

    static class PlainListener {
        @KafkaListener(id = "plain", topics = {"fixture.a.v1", "fixture.b.v1"}, groupId = "fixture-plain")
        void receive(ConsumerRecord<String, String> record) {
        }
    }

    static class Rejected extends RuntimeException {
    }

    static class RejectingListener {
        @KafkaListener(id = "rejecting", topics = "fixture.a.v1", groupId = "fixture-rejecting",
                containerFactory = "rejectingFactory")
        void receive(ConsumerRecord<String, String> record) {
            throw new Rejected();
        }
    }

    static class SlowListener {
        @KafkaListener(id = "slow", topics = "fixture.a.v1", groupId = "fixture-slow", containerFactory = "slowFactory")
        void receive(ConsumerRecord<String, String> record) {
        }
    }

    static class PersistentListener {
        @KafkaListener(id = "persistent", topics = "fixture.a.v1", groupId = "fixture-persistent",
                containerFactory = "persistentFactory")
        void receive(ConsumerRecord<String, String> record) {
        }
    }
}
