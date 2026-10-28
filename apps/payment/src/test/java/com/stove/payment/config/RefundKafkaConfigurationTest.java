package com.stove.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;

class RefundKafkaConfigurationTest {
    @Test void supplierDispatchBatchLimitSurvivesEndpointContainerCreation() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(RefundKafkaConfiguration.class)
                .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1", "spring.kafka.listener.auto-startup=false")
                .run(application -> {
                    KafkaListenerContainerFactory<?> factory = application.getBean("paymentRefundExecutionListenerContainerFactory",
                            KafkaListenerContainerFactory.class);
                    MethodKafkaListenerEndpoint<String, String> endpoint = new MethodKafkaListenerEndpoint<>();
                    endpoint.setId("refund-fixture"); endpoint.setGroupId("refund-fixture"); endpoint.setTopics("orders");
                    DefaultMessageHandlerMethodFactory methods = new DefaultMessageHandlerMethodFactory();
                    methods.afterPropertiesSet(); endpoint.setMessageHandlerMethodFactory(methods);
                    endpoint.setBean(new ListenerFixture()); endpoint.setMethod(ListenerFixture.class.getMethod("receive", String.class));
                    MessageListenerContainer container = factory.createListenerContainer(endpoint);
                    assertThat(container.getContainerProperties().getKafkaConsumerProperties())
                            .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
                });
    }

    public static class ListenerFixture { public void receive(String message) { } }
}
