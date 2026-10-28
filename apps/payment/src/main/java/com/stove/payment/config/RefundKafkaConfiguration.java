package com.stove.payment.config;

import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

@Configuration(proxyBeanMethods = false)
public class RefundKafkaConfiguration {
    @Bean ConcurrentKafkaListenerContainerFactory<Object, Object> paymentRefundExecutionListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers) {
        // Boot's factory configuration signature uses Object; domain inputs remain strongly typed.
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumers);
        Properties properties = new Properties();
        // Each record can wait for supplier HTTP; do not process a large batch between Kafka polls.
        properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");
        // Endpoint initialization replaces the prototype's consumer properties; customize the actual container last.
        factory.setContainerCustomizer(container -> container.getContainerProperties().setKafkaConsumerProperties(properties));
        return factory;
    }
}
