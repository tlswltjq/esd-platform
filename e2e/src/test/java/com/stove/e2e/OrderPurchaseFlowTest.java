package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.*;
import com.stove.common.kafka.contract.payload.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.header.Header;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Completed publication services + Order; payment results are explicit test fixtures. */
@Tag("order")
class OrderPurchaseFlowTest {
    private static Path evidence() { return Path.of(System.getProperty("stove.e2e.order-evidence")); }
    private static JsonNode saved() throws Exception { return JSON.readTree(Files.readString(evidence())); }
    private static Account buyer(JsonNode s) throws Exception { return login(s.path("buyerEmail").asText(), "creator-password-1234"); }
    private static Account buyer(JsonNode s, String name) throws Exception {
        return login(s.path(name + "BuyerEmail").asText(), "creator-password-1234");
    }
    private static String detail(String no) { return "/api/v1/orders/" + no; }
    private static void publish(long project, Account creator, Account reviewer) {
        long review = request(project, creator, reviewer);
        assertThat(Stove.gateway.post(reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        waitForResult(project, review, creator, "APPROVED");
        assertThat(Stove.gateway.post(path(project) + "/release-requests", null, creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(data(project, creator).path("currentReleaseRequest").path("status").asText()).isEqualTo("COMPLETED"));
    }
    private static JsonNode requestFor(JsonNode product) {
        return JSON.valueToTree(Map.of("requestId", UUID.randomUUID().toString(), "productCode", product.path("productCode").asText(),
                "expectedContentVersion", product.path("contentVersion").asLong(), "expectedAmount", product.path("price").asLong()));
    }
    private static JsonNode create(JsonNode input, Account buyer) {
        E2eClient.Response response = Stove.gateway.post("/api/v1/orders", input, buyer.headers());
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.data().path("status").asText()).isEqualTo("PENDING");
        return response.data();
    }
    private static JsonNode order(String number, Account buyer) {
        E2eClient.Response response = Stove.gateway.get(detail(number), buyer.headers());
        assertThat(response.status()).isEqualTo(200); return response.data();
    }
    private static void state(String number, Account buyer, String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(order(number, buyer).path("status").asText()).isEqualTo(expected));
    }
    private static Map<String,Object> kafkaConfig() {
        return new HashMap<>(Map.of("bootstrap.servers", System.getProperty("stove.e2e.kafka-brokers")));
    }
    private static KafkaConsumer<String,String> consumer(String topic) {
        Map<String, Object> config = kafkaConfig(); config.put("group.id", "order-evidence-" + UUID.randomUUID());
        config.put("auto.offset.reset", "earliest"); config.put("enable.auto.commit", false);
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<String,String>(config); consumer.subscribe(List.of(topic)); return consumer;
    }
    private static Map<String,OrderPlacedEvent> placed(Set<String> numbers) throws Exception {
        HashMap<String, OrderPlacedEvent> events = new HashMap<String,OrderPlacedEvent>();
        try (KafkaConsumer<String, String> consumer = consumer(Topics.ORDER)) {
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (events.size() < numbers.size() && System.nanoTime() < end)
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    Header header = record.headers().lastHeader(EventHeaders.EVENT_TYPE);
                    if (header != null && EventType.ORDER_PLACED.equals(new String(header.value(), StandardCharsets.UTF_8))) {
                        OrderPlacedEvent event = JSON.readValue(record.value(), OrderPlacedEvent.class);
                        if (numbers.contains(event.orderNo())) {
                            assertThat(record.key()).isEqualTo(event.orderNo());
                            assertThat(event.discountAmount()).isZero(); events.put(event.orderNo(), event);
                        }
                    }
                }
        }
        assertThat(events.keySet()).containsExactlyInAnyOrderElementsOf(numbers); return events;
    }
    private static void send(DomainEvent event) throws Exception {
        Map<String, Object> config = kafkaConfig();
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (KafkaProducer<String, String> producer = new KafkaProducer<String,String>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<String,String>(event.topic(), event.partitionKey(), JSON.writeValueAsString(event));
            record.headers().add(EventHeaders.EVENT_ID, event.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test void preparesOrdersWithRelayPaused() throws Exception {
        ReviewJourney.Account creator = account(true); ReviewJourney.Account buyer = account(false); ReviewJourney.Account other = account(false);
        ReviewJourney.Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD); ReviewJourney.Project project = project(creator);
        String code = data(project.id(), creator).path("productCode").asText();
        JsonNode unpublished = JSON.valueToTree(Map.of("requestId", UUID.randomUUID().toString(), "productCode", code, "expectedContentVersion", 1, "expectedAmount", 1000));
        assertThat(Stove.gateway.post("/api/v1/orders", unpublished, buyer.headers()).status()).isEqualTo(404);
        publish(project.id(), creator, reviewer);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
        JsonNode oldProduct = Stove.gateway.get("/api/v1/storefront/products/" + code).data();
        JsonNode firstRequest = requestFor(oldProduct); JsonNode first = create(firstRequest, buyer);
        assertThat(first.path("amount").asLong()).isEqualTo(1000);
        assertThat(first.path("listAmount").asLong()).isEqualTo(1000);
        assertThat(first.path("discountAmount").asLong()).isZero();
        assertThat(Stove.gateway.post("/api/v1/orders", firstRequest, buyer.headers()).data()).isEqualTo(first);
        assertThat(Stove.gateway.get(detail(first.path("orderNo").asText()), other.headers()).status()).isEqualTo(404);
        assertThat(Stove.gateway.get("/api/v1/orders").status()).isEqualTo(401);
        assertThat(Stove.gateway.post("/api/v1/orders", requestFor(oldProduct), reviewer.headers()).status()).isEqualTo(403);
        JsonNode forged = firstRequest.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)forged)
                .put("buyerSubject", other.subject()).put("memberId", 999).put("status", "PAID");
        assertThat(Stove.gateway.post("/api/v1/orders", forged, buyer.headers()).data()).isEqualTo(first);
        JsonNode conflict = firstRequest.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)conflict).put("expectedAmount", 1);
        assertThat(Stove.gateway.post("/api/v1/orders", conflict, buyer.headers()).status()).isEqualTo(409);
        page(project.id(), creator, "Second public title", 2000); publish(project.id(), creator, reviewer);
        JsonNode current = Stove.gateway.get("/api/v1/products/by-code/" + code).data();
        assertThat(current.path("price").asLong()).isEqualTo(2000);
        assertThat(Stove.gateway.post("/api/v1/orders", requestFor(oldProduct), buyer.headers()).status()).isEqualTo(409);
        assertThat(order(first.path("orderNo").asText(), buyer)).isEqualTo(first);
        Account pendingBuyer = account(false); Account failedBuyer = account(false);
        JsonNode pendingRequest = requestFor(current); JsonNode pending = create(pendingRequest, pendingBuyer);
        JsonNode failedRequest = requestFor(current); JsonNode failed = create(failedRequest, failedBuyer);
        E2eClient.Response list = Stove.gateway.get("/api/v1/orders?size=1", buyer.headers());
        assertThat(list.data().path("totalElements").asLong()).isEqualTo(1);
        assertThat(list.data().path("content").size()).isEqualTo(1);
        ObjectNode s = JSON.createObjectNode().put("buyerEmail", buyer.email()).put("pendingBuyerEmail", pendingBuyer.email()).put("failedBuyerEmail", failedBuyer.email()).put("code", code);
        s.set("first", first); s.set("firstRequest", firstRequest); s.set("pending", pending);
        s.set("pendingRequest", pendingRequest); s.set("failed", failed); s.set("currentProduct", current);
        Files.writeString(evidence(), JSON.writeValueAsString(s));
    }
    @Test void queriesOrdersWhileBrokerDown() throws Exception {
        JsonNode s = saved(); ReviewJourney.Account buyer = buyer(s);
        assertThat(order(s.path("first").path("orderNo").asText(), buyer)).isEqualTo(s.path("first"));
        assertThat(Stove.gateway.post("/api/v1/orders", s.path("firstRequest"), buyer.headers()).data()).isEqualTo(s.path("first"));
        assertThat(Stove.gateway.get("/api/v1/orders", buyer.headers()).data().path("totalElements").asLong()).isEqualTo(1);
    }
    @Test void verifiesPublishedEventsAndPaymentResults() throws Exception {
        JsonNode s = saved(); ReviewJourney.Account buyer = buyer(s);
        String first = s.path("first").path("orderNo").asText(), pending = s.path("pending").path("orderNo").asText(), failed = s.path("failed").path("orderNo").asText();
        Map<String, OrderPlacedEvent> events = placed(Set.of(first, pending, failed)); OrderPlacedEvent a = events.get(first); OrderPlacedEvent b = events.get(pending); OrderPlacedEvent f = events.get(failed);
        assertThat(a.buyerSubject()).isEqualTo(buyer.subject()); assertThat(a.amount()).isEqualTo(1000);
        assertThat(b.amount()).isEqualTo(2000); assertThat(a.contentVersion()).isLessThan(b.contentVersion());
        OrderPaymentCompletedEvent success = OrderPaymentCompletedEvent.of(first, a.eventId(), "fixture-" + UUID.randomUUID(), buyer.subject(), a.amount(), a.currency());
        send(success); send(success);
        send(OrderPaymentFailedEvent.of(failed, f.eventId(), "fixture-" + UUID.randomUUID(), f.buyerSubject(), f.amount(), f.currency(), "DECLINED", "Fixture final decline"));
        OrderPaymentCompletedEvent bad = OrderPaymentCompletedEvent.of(pending, b.eventId(), "fixture-" + UUID.randomUUID(), b.buyerSubject(), b.amount()-200, b.currency());
        try (KafkaConsumer<String, String> dlt = consumer(Topics.PAYMENT + ".DLT")) {
            send(bad); boolean found = false; long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (!found && System.nanoTime() < end)
                for (ConsumerRecord<String, String> record : dlt.poll(Duration.ofMillis(500))) if (record.value().contains(bad.eventId())) found = true;
            assertThat(found).isTrue();
        }
        state(first, buyer, "PAID"); state(failed, buyer(s, "failed"), "FAILED"); state(pending, buyer(s, "pending"), "PENDING");
        assertThat(order(first, buyer).path("amount").asLong()).isEqualTo(1000);
        ObjectNode changed = (com.fasterxml.jackson.databind.node.ObjectNode)s;
        changed.set("completion", JSON.valueToTree(success)); changed.set("paid", order(first, buyer));
        changed.set("failedResult", order(failed, buyer(s, "failed"))); Files.writeString(evidence(), JSON.writeValueAsString(changed));
    }
    @Test void queriesOrdersWithoutCatalog() throws Exception {
        JsonNode s = saved(); ReviewJourney.Account buyer = buyer(s);
        assertThat(Stove.gateway.get("/api/v1/storefront/products/" + s.path("code").asText()).status()).isEqualTo(200);
        assertThat(Stove.gateway.post("/api/v1/orders", s.path("firstRequest"), buyer.headers()).data()).isEqualTo(s.path("paid"));
        assertThat(Stove.gateway.post("/api/v1/orders", s.path("pendingRequest"), buyer(s, "pending").headers()).data()).isEqualTo(s.path("pending"));
        assertThat(Stove.gateway.post("/api/v1/orders", requestFor(s.path("currentProduct")), buyer.headers()).status()).isEqualTo(503);
        assertThat(Stove.gateway.get("/api/v1/orders", buyer.headers()).data().path("totalElements").asLong()).isEqualTo(1);
    }
    @Test void verifiesPersistedResultsAfterOrderRestart() throws Exception {
        JsonNode s = saved(); ReviewJourney.Account buyer = buyer(s);
        assertThat(order(s.path("paid").path("orderNo").asText(), buyer)).isEqualTo(s.path("paid"));
        assertThat(order(s.path("failedResult").path("orderNo").asText(), buyer(s, "failed"))).isEqualTo(s.path("failedResult"));
        send(JSON.treeToValue(s.path("completion"), OrderPaymentCompletedEvent.class));
        // The following phase also verifies the timestamp after the replay has been consumed.
        assertThat(Stove.gateway.post("/api/v1/orders", s.path("firstRequest"), buyer.headers()).data()).isEqualTo(s.path("paid"));
    }
    @Test void createsAfterCatalogRecovery() throws Exception {
        JsonNode s = saved(); ReviewJourney.Account buyer = buyer(s);
        assertThat(order(s.path("paid").path("orderNo").asText(), buyer)).isEqualTo(s.path("paid"));
        JsonNode recovered = create(requestFor(s.path("currentProduct")), buyer(s, "failed"));
        assertThat(recovered.path("amount").asLong()).isEqualTo(2000);
        placed(Set.of(recovered.path("orderNo").asText()));
        assertThat(Stove.gateway.get("/api/v1/orders", buyer.headers()).data().path("totalElements").asLong()).isEqualTo(1);
    }
}
