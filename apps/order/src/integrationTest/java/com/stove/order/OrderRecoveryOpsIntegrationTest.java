package com.stove.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static com.stove.order.OrderFixtures.order;
import static com.stove.order.OrderFixtures.completed;
import static com.stove.order.OrderFixtures.result;
import com.stove.common.kafka.contract.EventType;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.service.ApplyPaymentResult;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.messaging.outbox.OutboxMetrics;
import com.stove.common.messaging.outbox.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@TestPropertySource(properties = "stove.messaging.ops.enabled=true")
class OrderRecoveryOpsIntegrationTest extends OrderIntegrationTestBase {
    @Autowired OrderRepository orders;
    @Autowired ApplyPaymentResult apply;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumers;
    @Autowired OutboxEventRepository rows;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @LocalServerPort int port;

    @BeforeEach
    void clean() {
        listeners.stop();
        jdbc.update("DELETE FROM order_refund_attempt");
        jdbc.update("DELETE FROM order_license_grant");
        jdbc.update("DELETE FROM order_refund");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminRequeuesTheOriginalDeadEventAndTheRealRelayPublishesItWithoutChangingItsEvidence() {
        Order stored = orders.saveAndFlush(order());
        apply.execute(EventType.ORDER_PAYMENT_COMPLETED, result(completed(stored)));
        Map<String, Object> original = jdbc.queryForMap("SELECT event_id,payload,topic,partition_key,created_at FROM outbox_event");
        String eventId = original.get("event_id").toString();
        KafkaTemplate<String, String> failed = mock(KafkaTemplate.class);
        when(failed.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("fixture broker unavailable")));
        relay(failed).relay();
        assertThat(status(eventId)).isEqualTo("DEAD");
        String path = "/api/v1/ops/outbox/dead/" + eventId + "/requeue";
        assertThat(http(HttpMethod.POST, path, "MEMBER")).isEqualTo(403);
        assertThat(status(eventId)).isEqualTo("DEAD");
        assertThat(http(HttpMethod.GET, "/api/v1/ops/outbox/dead", "ADMIN")).isEqualTo(200);
        assertThat(http(HttpMethod.POST, path, "ADMIN")).isEqualTo(200);
        assertThat(status(eventId)).isEqualTo("PENDING");
        relay(kafka).relay();
        assertThat(status(eventId)).isEqualTo("SENT");
        assertThat(jdbc.queryForMap("SELECT event_id,payload,topic,partition_key,created_at FROM outbox_event"))
                .isEqualTo(original);
        assertThat(http(HttpMethod.POST, path, "ADMIN")).isEqualTo(404);
        assertThat(http(HttpMethod.POST, "/api/v1/ops/outbox/dead/requeue-all", "ADMIN")).isEqualTo(403);

        Map<String, Object> config = new HashMap<>(consumers.getConfigurationProperties());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (Consumer<String, String> observer = new DefaultKafkaConsumerFactory<String, String>(config)
                .createConsumer("p3-observer-" + UUID.randomUUID())) {
            String topic = original.get("topic").toString();
            observer.assign(observer.partitionsFor(topic).stream()
                    .map(partition -> new TopicPartition(topic, partition.partition())).toList());
            ConsumerRecord<String, String> found = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (found == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : observer.poll(Duration.ofMillis(500))) {
                    if (new String(record.headers().lastHeader(EventHeaders.EVENT_ID).value(), StandardCharsets.UTF_8).equals(eventId)) {
                        found = record;
                    }
                }
            }
            assertThat(found).isNotNull();
            assertThat(found.key()).isEqualTo(original.get("partition_key"));
            assertThat(found.value()).isEqualTo(original.get("payload"));
        }
    }

    private OutboxRelay relay(KafkaTemplate<String, String> template) {
        return new OutboxRelay(rows, template, 200, 1, 1,
                new OutboxMetrics(new SimpleMeterRegistry(), rows), new TransactionTemplate(transactions));
    }

    private String status(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId);
    }

    private int http(HttpMethod method, String path, String role) {
        return RestClient.create("http://127.0.0.1:" + port).method(method).uri(path)
                .header("Authorization", "Bearer " + token("operator", role))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
