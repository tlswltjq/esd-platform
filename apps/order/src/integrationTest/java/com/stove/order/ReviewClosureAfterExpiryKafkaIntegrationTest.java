package com.stove.order;

import static com.stove.order.OrderFixtures.product;
import static com.stove.order.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.DomainEvent;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaymentFailedEvent;
import com.stove.order.core.domain.Order;
import com.stove.order.core.domain.OrderRepository;
import com.stove.order.core.domain.OrderStatus;
import com.stove.order.core.port.CatalogProducts;
import com.stove.order.core.service.ApplyPaymentResult;
import com.stove.order.core.service.CloseExpiredOrder;
import com.stove.order.core.service.CreateOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 결정 115: Payment가 운영자 종결을 확정하는 사이 주문이 스스로 30분 만료되면, 늦게 도착한 REVIEW_CLOSED 실패가 실제 Kafka
 * 소비에서 재시도·DLT로 가지 않고 만료를 유지한다. 그 밖의 결과는 지금처럼 DLT로 격리된다.
 */
class ReviewClosureAfterExpiryKafkaIntegrationTest extends OrderIntegrationTestBase {
    @Autowired CreateOrder create;
    @Autowired CloseExpiredOrder expire;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaTemplate<?, ?> kafka;
    @MockitoBean CatalogProducts catalog;
    @MockitoBean Clock clock;

    @BeforeEach void prepare() { clean(); when(catalog.detail(anyString())).thenReturn(product()); }
    @AfterEach void clean() {
        listeners.stop();
        jdbc.update("DELETE FROM order_payment_admission");
        jdbc.update("DELETE FROM purchase_order");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test void aReviewClosureThatCrossesTheOrdersOwnExpiryIsConsumedWithoutRetryOrDeadLetter() throws Exception {
        Order closed = expired("usr_buyer");
        Order declined = expired("other-buyer");
        OrderPaymentFailedEvent review = failure(closed, "REVIEW_CLOSED");
        OrderPaymentFailedEvent control = failure(declined, "DECLINED");
        try (KafkaConsumer<String, String> dlt = dltConsumer(); AdminClient admin = admin()) {
            paymentResults().start();
            RecordMetadata first = send(review);
            RecordMetadata redelivered = send(review);
            RecordMetadata other = send(control);
            ConsumerRecord<String, String> dead = receiveDlt(dlt, control.eventId());
            assertThat(dead.key()).isEqualTo(declined.getOrderNo());
            for (RecordMetadata sent : List.of(first, redelivered, other)) awaitConsumed(admin, sent);
            assertNoDeadLetter(dlt, review.eventId());
        }
        Order kept = orders.findById(closed.getId()).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(kept.paymentResult()).isNull();
        assertThat(kept.getFailureReasonCode()).isNull();
        assertThat(inbox(review.eventId())).isEqualTo(1);
        assertThat(inbox(control.eventId())).isZero();
        assertThat(orders.findById(declined.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='OrderPaid'", Integer.class)).isZero();
    }

    private Order expired(String buyer) {
        Order order = create.execute(buyer, request());
        when(clock.instant()).thenReturn(order.getExpiresAt());
        assertThat(expire.execute(order.getOrderNo())).isTrue();
        return order;
    }
    private static OrderPaymentFailedEvent failure(Order order, String reasonCode) {
        return OrderPaymentFailedEvent.of(order.getOrderNo(), order.getRequestEventId(), OrderFixtures.id(), order.getBuyerSubject(),
                order.getAmount(), order.getCurrency(), reasonCode, "Payment approval review closed with PG evidence CANCELLED");
    }
    private MessageListenerContainer paymentResults() {
        return listeners.getListenerContainers().stream().filter(container -> ApplyPaymentResult.CONSUMER_GROUP.equals(container.getGroupId()))
                .findFirst().orElseThrow();
    }
    private RecordMetadata send(DomainEvent event) throws Exception {
        Message<String> message = MessageBuilder.withPayload(mapper.writeValueAsString(event))
                .setHeader(KafkaHeaders.TOPIC, event.topic()).setHeader(KafkaHeaders.KEY, event.partitionKey())
                .setHeader(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8))
                .setHeader(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8)).build();
        return kafka.send(message).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }
    private Map<String, Object> connection() {
        return Map.of("bootstrap.servers", kafka.getProducerFactory().getConfigurationProperties().get("bootstrap.servers"));
    }
    private AdminClient admin() { return AdminClient.create(connection()); }
    private KafkaConsumer<String, String> dltConsumer() {
        HashMap<String, Object> properties = new HashMap<>(connection());
        properties.put("group.id", "review-closure-dlt-" + OrderFixtures.id());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", false);
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(Topics.PAYMENT + ".DLT"));
        return consumer;
    }
    private ConsumerRecord<String, String> receiveDlt(KafkaConsumer<String, String> consumer, String eventId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300)))
                if (fromPaymentResults(record) && record.value().contains(eventId)) return record;
        }
        throw new AssertionError("결제 결과 원문이 DLT에 보존되지 않았습니다: " + eventId);
    }
    /** 소비 오프셋이 커밋된 뒤에는 실패했다면 DLT 발행이 끝나 있다. DLT 끝까지 다시 읽어 원문이 없음을 확인한다. */
    private void assertNoDeadLetter(KafkaConsumer<String, String> consumer, String eventId) {
        Set<TopicPartition> partitions = consumer.assignment();
        assertThat(partitions).isNotEmpty();
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
        consumer.seekToBeginning(partitions);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
            if (System.nanoTime() > deadline) throw new AssertionError("DLT를 끝까지 읽지 못했습니다");
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300)))
                assertThat(fromPaymentResults(record) && record.value().contains(eventId)).as("DLT record for " + eventId).isFalse();
        }
    }
    private static boolean fromPaymentResults(ConsumerRecord<String, String> record) {
        return record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP) != null
                && ApplyPaymentResult.CONSUMER_GROUP.equals(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).value(), StandardCharsets.UTF_8));
    }
    private void awaitConsumed(AdminClient admin, RecordMetadata record) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(committed(admin, record)).isGreaterThanOrEqualTo(record.offset() + 1));
    }
    private long committed(AdminClient admin, RecordMetadata record) throws Exception {
        try {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(ApplyPaymentResult.CONSUMER_GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(new TopicPartition(record.topic(), record.partition()));
            return committed == null ? -1 : committed.offset();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof GroupIdNotFoundException) return -1;
            throw failure;
        }
    }
    private int inbox(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=? AND consumer_group=?", Integer.class, eventId, ApplyPaymentResult.CONSUMER_GROUP);
    }
}
