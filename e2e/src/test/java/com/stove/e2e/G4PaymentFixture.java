package com.stove.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaymentCompletedEvent;
import com.stove.common.kafka.contract.payload.OrderPlacedEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;

/** Payment is a contract fixture; Order admission, OrderPaid, License and Download are real apps. */
final class G4PaymentFixture {
    private G4PaymentFixture() {}

    static OrderPlacedEvent placed(String number) throws Exception {
        Map<String, Object> config = config();
        config.put("group.id", "g4-evidence-" + UUID.randomUUID());
        config.put("auto.offset.reset", "earliest");
        config.put("enable.auto.commit", false);
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(Topics.ORDER));
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    Header type = record.headers().lastHeader(EventHeaders.EVENT_TYPE);
                    if (type == null || !EventType.ORDER_PLACED.equals(new String(type.value(), StandardCharsets.UTF_8))) continue;
                    OrderPlacedEvent event = ReviewJourney.JSON.readValue(record.value(), OrderPlacedEvent.class);
                    if (!number.equals(event.orderNo())) continue;
                    assertThat(record.key()).isEqualTo(number);
                    assertThat(new String(record.headers().lastHeader(EventHeaders.EVENT_ID).value(), StandardCharsets.UTF_8))
                            .isEqualTo(event.eventId());
                    return event;
                }
            }
        }
        throw new AssertionError("Real OrderPlaced was not delivered for " + number);
    }

    static Map<String, Object> admission(OrderPlacedEvent event) {
        return Map.of("admissionId", UUID.randomUUID().toString(), "requestEventId", event.eventId(),
                "paymentId", "g4-fixture-" + UUID.randomUUID(), "buyerSubject", event.buyerSubject(),
                "amount", event.amount(), "currency", event.currency());
    }

    static E2eClient.Response admit(String number, Object body) throws Exception {
        JsonNode secrets = ReviewJourney.JSON.readTree(Files.readString(Path.of(System.getProperty("stove.e2e.g4-secrets"))));
        return Stove.consumers.get("order").post("/internal/v1/orders/" + number + "/payment-admissions", body,
                Map.of("X-Payment-Service-Key", secrets.path("paymentOrderServiceKey").asText()));
    }

    static void complete(OrderPlacedEvent placed, String paymentId) throws Exception {
        OrderPaymentCompletedEvent event = OrderPaymentCompletedEvent.of(placed.orderNo(), placed.eventId(), paymentId,
                placed.buyerSubject(), placed.amount(), placed.currency());
        Map<String, Object> config = config();
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.partitionKey(), ReviewJourney.JSON.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(15, TimeUnit.SECONDS);
        }
    }

    private static Map<String, Object> config() {
        return new HashMap<>(Map.of("bootstrap.servers", System.getProperty("stove.e2e.kafka-brokers")));
    }
}
