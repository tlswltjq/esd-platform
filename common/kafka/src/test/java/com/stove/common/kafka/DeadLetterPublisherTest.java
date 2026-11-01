package com.stove.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

class DeadLetterPublisherTest {
    @Test
    @SuppressWarnings("unchecked")
    void failedDltSendMustFailRecoveryAndMustNotCountAsDeadLettered() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ProducerFactory<String, String> factory = mock(ProducerFactory.class);
        when(kafka.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(Map.of(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000));
        when(kafka.partitionsFor(anyString())).thenReturn(List.of(
                new PartitionInfo("recovery.DLT", 0, null, new Node[0], new Node[0])));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("DLT broker unavailable")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerRecordRecoverer publisher = DeadLetterPublisher.to(kafka, new DeadLetterMetrics(registry));

        assertThatThrownBy(() -> publisher.accept(new ConsumerRecord<>("recovery", 0, 0, "order", "{}"),
                new IllegalStateException("business database unavailable"))).isInstanceOf(KafkaException.class);

        assertThat(registry.find("stove.kafka.dead-lettered").counters()).isEmpty();
    }
}
