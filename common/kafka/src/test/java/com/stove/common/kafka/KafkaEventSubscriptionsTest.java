package com.stove.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stove.common.event.EventHandler;
import com.stove.common.event.EventSubscriber;
import com.stove.common.event.InboundMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * 업무 리스너가 Kafka 를 모르고도 같은 구독(토픽·그룹·재시도·격리)을 받는지 본다.
 * 브로커 없이 컨테이너를 만들기만 한다({@code auto-startup=false}).
 */
class KafkaEventSubscriptionsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, KafkaConsumerAutoConfiguration.class))
            .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false");

    @Test
    void registersOneContainerPerHandlerWithItsTopicsAndGroup() {
        runner.withBean("plainHandler", PlainHandler.class).run(context -> {
            MessageListenerContainer container = context.getBean(KafkaListenerEndpointRegistry.class)
                    .getListenerContainer("plainHandler");
            assertThat(container).isNotNull();
            assertThat(container.getGroupId()).isEqualTo("fixture-plain");
            assertThat(container.getContainerProperties().getTopics()).containsExactly("fixture.a.v1", "fixture.b.v1");
            // 특별한 선언이 없으면 공용 오류 처리기(재시도 뒤 DLT)를 그대로 쓴다.
            assertThat(errorHandler(container)).isSameAs(context.getBean("stoveKafkaErrorHandler"));
        });
    }

    @Test
    void deliversTheRecordAsABrokerNeutralMessage() {
        PlainHandler handler = new PlainHandler();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("fixture.a.v1", 3, 10L, "ORD-1", "{}");
        record.headers().add(EventHeaders.EVENT_ID, "EVT-old".getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_ID, "EVT-1".getBytes(StandardCharsets.UTF_8));

        new KafkaEventSubscriptions.Receiver(handler).onMessage(record);

        InboundMessage message = handler.received.get(0);
        assertThat(message.topic()).isEqualTo("fixture.a.v1");
        assertThat(message.key()).isEqualTo("ORD-1");
        assertThat(message.payload()).isEqualTo("{}");
        assertThat(message.header(EventHeaders.EVENT_ID)).as("같은 헤더는 마지막 값").isEqualTo("EVT-1");
        assertThat(message.headerValues(EventHeaders.EVENT_ID)).as("중복도 잃지 않는다").containsExactly("EVT-old", "EVT-1");
        assertThat(message.origin()).isEqualTo("partition=3 offset=10");
    }

    @Test
    void noRetryExceptionsGoStraightToQuarantine() {
        runner.withBean("rejectingHandler", RejectingHandler.class).run(context -> {
            MessageListenerContainer container = context.getBean(KafkaListenerEndpointRegistry.class)
                    .getListenerContainer("rejectingHandler");
            CommonErrorHandler handler = errorHandler(container);
            assertThat(handler).isNotSameAs(context.getBean("stoveKafkaErrorHandler"));
        });
        List<ConsumerRecord<?, ?>> quarantined = new ArrayList<>();
        CommonErrorHandler handler = DeadLetterErrorHandlers.create(
                (record, exception) -> quarantined.add(record), Rejected.class);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("fixture.a.v1", 0, 0L, "K", "{}");

        // 첫 실패에서 바로 회복(격리)했다 = 재시도하지 않았다.
        boolean recovered = handler.handleOne(new Rejected(), record, mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isTrue();
        assertThat(quarantined).containsExactly(record);
    }

    @Test
    void oneAtATimeLimitsEachPollToOneRecord() {
        runner.withBean("slowHandler", SlowHandler.class).run(context -> {
            MessageListenerContainer container = context.getBean(KafkaListenerEndpointRegistry.class)
                    .getListenerContainer("slowHandler");
            assertThat(container.getContainerProperties().getKafkaConsumerProperties())
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        });
    }

    @Test
    void refusesASubscriberThatIsNotAHandler() {
        runner.withBean("notAHandler", NotAHandler.class).run(context ->
                assertThat(context).hasFailed());
    }

    private static CommonErrorHandler errorHandler(MessageListenerContainer container) {
        return ((AbstractMessageListenerContainer<?, ?>) container).getCommonErrorHandler();
    }

    @EventSubscriber(topics = {"fixture.a.v1", "fixture.b.v1"}, group = "fixture-plain")
    static class PlainHandler implements EventHandler {
        final List<InboundMessage> received = new ArrayList<>();

        @Override
        public void receive(InboundMessage message) {
            received.add(message);
        }
    }

    static class Rejected extends RuntimeException {
    }

    @EventSubscriber(topics = "fixture.a.v1", group = "fixture-rejecting", noRetryFor = Rejected.class)
    static class RejectingHandler implements EventHandler {
        @Override
        public void receive(InboundMessage message) {
            throw new Rejected();
        }
    }

    @EventSubscriber(topics = "fixture.a.v1", group = "fixture-slow", oneAtATime = true)
    static class SlowHandler implements EventHandler {
        @Override
        public void receive(InboundMessage message) {
        }
    }

    @EventSubscriber(topics = "fixture.a.v1", group = "fixture-wrong")
    static class NotAHandler {
    }
}
