package com.stove.common.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.common.contract.EventHeaders;
import com.stove.common.event.OutboundMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaMessagePublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final KafkaMessagePublisher publisher = new KafkaMessagePublisher(kafka);

    @Test
    void sendsTheOrderKeyAsTheKafkaKeyWithHeadersInOrder() throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EventHeaders.EVENT_ID, "EVT-1");
        headers.put(EventHeaders.EVENT_TYPE, "OrderPaid");

        publisher.publish(new OutboundMessage("stove.order.v1", "ORD-1", "{}", headers)).get();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        ProducerRecord<String, String> record = sent.getValue();
        assertThat(record.topic()).isEqualTo("stove.order.v1");
        assertThat(record.key()).as("같은 키는 같은 파티션").isEqualTo("ORD-1");
        assertThat(record.value()).isEqualTo("{}");
        List<String> names = new ArrayList<>();
        for (Header header : record.headers()) {
            names.add(header.key());
        }
        assertThat(names).containsExactly(EventHeaders.EVENT_ID, EventHeaders.EVENT_TYPE);
        assertThat(new String(record.headers().lastHeader(EventHeaders.EVENT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo("EVT-1");
    }

    @Test
    void aFailedSendFailsThePublication() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        CompletableFuture<Void> published = publisher.publish(new OutboundMessage("t", "K", "{}", Map.of()));

        assertThatThrownBy(published::get).isInstanceOf(ExecutionException.class).hasMessageContaining("broker down");
    }
}
