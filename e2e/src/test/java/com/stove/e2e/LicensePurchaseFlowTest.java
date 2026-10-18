package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.Account;
import static com.stove.e2e.ReviewJourney.JSON;
import static com.stove.e2e.ReviewJourney.Project;
import static com.stove.e2e.ReviewJourney.REVIEWER_EMAIL;
import static com.stove.e2e.ReviewJourney.REVIEWER_PASSWORD;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.path;
import static com.stove.e2e.ReviewJourney.project;
import static com.stove.e2e.ReviewJourney.request;
import static com.stove.e2e.ReviewJourney.reviewPath;
import static com.stove.e2e.ReviewJourney.waitForResult;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.common.kafka.contract.payload.OrderPaidEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("license")
class LicensePurchaseFlowTest {
    static Path evidence() { return Path.of(System.getProperty("stove.e2e.license-evidence")); }
    static ObjectNode saved() throws Exception { return (ObjectNode) JSON.readTree(Files.readString(evidence())); }
    static void save(ObjectNode s) throws Exception { Files.writeString(evidence(), JSON.writeValueAsString(s)); }
    static Account buyer(JsonNode s) throws Exception { return login(s.path("buyerEmail").asText(), "creator-password-1234"); }
    static Account other(JsonNode s) throws Exception { return login(s.path("otherEmail").asText(), "creator-password-1234"); }
    static Account newBuyer(ObjectNode s, String name) throws Exception {
        Account account = account(false); s.put(name + "BuyerEmail", account.email()); save(s); return account;
    }
    static Account buyer(JsonNode s, String name) throws Exception {
        return login(s.path(name + "BuyerEmail").asText(), "creator-password-1234");
    }
    static Map<String, Object> input(ObjectNode s) {
        JsonNode product = s.path("product");
        return Map.of("requestId", UUID.randomUUID().toString(), "productCode", code(s),
                "expectedContentVersion", product.path("contentVersion").asLong(), "expectedAmount", product.path("price").asLong());
    }
    static void rejected(ObjectNode s, Account account, String error) {
        E2eClient.Response response = Stove.gateway.post("/api/v1/orders", input(s), account.headers());
        assertThat(response.status()).as(response.toString()).isEqualTo(409);
        assertThat(response.body().toString()).contains(error);
    }
    static String code(JsonNode s) { return s.path("product").path("productCode").asText(); }
    static JsonNode get(String resource, String key, Account account) {
        E2eClient.Response response = Stove.gateway.get("/api/v1/" + resource + "/" + key, account.headers());
        assertThat(response.status()).as(response.toString()).isEqualTo(200); return response.data();
    }
    static JsonNode state(ObjectNode s, String name, Account account, String expected) {
        String no = s.path(name).path("orderNo").asText();
        Awaitility.await().atMost(Duration.ofSeconds(50)).untilAsserted(() -> {
            assertThat(get("orders", no, account).path("status").asText()).isEqualTo(expected);
            assertThat(get("payments", no, account).path("status").asText()).isEqualTo(expected);
        });
        return get("orders", no, account);
    }
    static JsonNode create(ObjectNode s, String name, Account account) throws Exception {
        Map<String, Object> request = input(s);
        E2eClient.Response response = Stove.gateway.post("/api/v1/orders", request, account.headers());
        s.set(name + "Request", JSON.valueToTree(request));
        assertThat(response.status()).as(response.toString()).isEqualTo(200); s.set(name, response.data()); save(s); return response.data();
    }
    static JsonNode library(ObjectNode s, Account account) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/library/" + code(s), account.headers()).status()).isEqualTo(200));
        return get("library", code(s), account);
    }
    static void stableLibrary(ObjectNode s) throws Exception {
        Account buyer = buyer(s); assertThat(library(s, buyer)).isEqualTo(s.path("firstLicense"));
        E2eClient.Response page = Stove.gateway.get("/api/v1/library?memberId=999&buyerSubject=other", buyer.headers());
        assertThat(page.status()).isEqualTo(200); assertThat(page.data().path("totalElements").asInt()).isEqualTo(1);
    }
    @Test void preparePendingPurchase() throws Exception {
        Account creator = account(true); Account buyer = account(false); Account other = account(false);
        Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD); Project project = project(creator);
        long review = request(project.id(), creator, reviewer);
        assertThat(Stove.gateway.post(reviewPath(review) + "/approve", null, reviewer.headers()).status()).isEqualTo(200);
        waitForResult(project.id(), review, creator, "APPROVED");
        assertThat(Stove.gateway.post(path(project.id()) + "/release-requests", null, creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(data(project.id(), creator).path("currentReleaseRequest").path("status").asText()).isEqualTo("COMPLETED"));
        String code = data(project.id(), creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
        ObjectNode s = JSON.createObjectNode().put("buyerEmail", buyer.email()).put("otherEmail", other.email());
        s.set("product", Stove.gateway.get("/api/v1/storefront/products/" + code).data());
        create(s, "approved", buyer); state(s, "approved", buyer, "PENDING");
        rejected(s, buyer, "PURCHASE_IN_PROGRESS");
        assertThat(Stove.gateway.get("/api/v1/library", buyer.headers()).data().path("totalElements").asInt()).isZero();
        assertThat(Stove.gateway.get("/api/v1/library/" + code, buyer.headers()).status()).isEqualTo(404); save(s);
    }
    @Test void approvedOrderGrantsOneProduct() throws Exception {
        ObjectNode s = saved(); Account buyer = buyer(s); state(s, "approved", buyer, "PAID");
        JsonNode license = library(s, buyer); assertThat(license.path("productId")).isEqualTo(s.path("product").path("productId"));
        assertThat(license.path("title")).isEqualTo(s.path("product").path("title")); assertThat(license.path("licenseId").asText()).isNotBlank();
        s.set("firstLicense", license); save(s); stableLibrary(s);
        rejected(s, buyer, "PRODUCT_ALREADY_PURCHASED");
    }
    @Test void declinedOrderDoesNotAddAPurchase() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "declined"); create(s, "declined", buyer); state(s, "declined", buyer, "FAILED");
        assertThat(Stove.gateway.get("/api/v1/library/" + code(s), buyer.headers()).status()).isEqualTo(404); stableLibrary(s);
    }
    @Test void rejectsRepurchaseAndRetriesAFailedOrder() throws Exception {
        ObjectNode s = saved(); rejected(s, buyer(s), "PRODUCT_ALREADY_PURCHASED");
        Account buyer = buyer(s, "declined"); create(s, "retry", buyer); state(s, "retry", buyer, "PAID");
        assertThat(s.path("retry").path("orderNo")).isNotEqualTo(s.path("declined").path("orderNo"));
        assertThat(get("orders", s.path("declined").path("orderNo").asText(), buyer).path("status").asText()).isEqualTo("FAILED");
        s.set("retryLicense", library(s, buyer)); save(s); stableLibrary(s);
    }
    @Test void anotherBuyerGetsASeparateRight() throws Exception {
        ObjectNode s = saved(); Account other = other(s); create(s, "other", other); state(s, "other", other, "PAID");
        JsonNode license = library(s, other); assertThat(license.path("licenseId")).isNotEqualTo(s.path("firstLicense").path("licenseId"));
        s.set("otherLicense", license); save(s); stableLibrary(s);
    }
    @Test void payWhileLicenseIsStopped() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "offline"); create(s, "offline", buyer); state(s, "offline", buyer, "PAID");
        rejected(s, buyer, "PRODUCT_ALREADY_PURCHASED");
    }
    @Test void catchesUpAfterLicenseRestart() throws Exception {
        ObjectNode s = saved(); s.set("offlineLicense", library(s, buyer(s, "offline"))); save(s); stableLibrary(s);
    }
    @Test void grantCommitsBeforeTheConsumerProcessExits() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "beforeAck"); create(s, "beforeAck", buyer); state(s, "beforeAck", buyer, "PAID");
    }
    @Test void redeliveryAfterUnacknowledgedCommitPreservesTheRight() throws Exception {
        ObjectNode s = saved(); s.set("beforeAckLicense", library(s, buyer(s, "beforeAck"))); save(s); stableLibrary(s);
    }
    @Test void preparePaymentBeforeOrderRelayPause() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "relay"); create(s, "relay", buyer); state(s, "relay", buyer, "PENDING"); stableLibrary(s);
    }
    @Test void readsExistingLibraryWhileCatalogAndBrokerAreStopped() throws Exception { stableLibrary(saved()); }
    @Test void catchesUpOriginalPaidOutboxAndReplaysIt() throws Exception {
        ObjectNode s = saved(); Account buyer = buyer(s, "relay"); state(s, "relay", buyer, "PAID");
        s.set("relayLicense", library(s, buyer)); save(s); stableLibrary(s);
        HashMap<String, Object> config = new HashMap<>(); config.put("bootstrap.servers", System.getProperty("stove.e2e.kafka-brokers"));
        HashMap<String, Object> consumerConfig = new HashMap<>(config); consumerConfig.put("group.id", "license-evidence-" + UUID.randomUUID());
        consumerConfig.put("auto.offset.reset", "earliest"); consumerConfig.put("enable.auto.commit", false);
        consumerConfig.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"); consumerConfig.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        OrderPaidEvent original = null; String originalPayload = null;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig)) {
            consumer.subscribe(List.of(Topics.ORDER)); long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (original == null && System.nanoTime() < deadline) for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                String type = new String(record.headers().lastHeader(EventHeaders.EVENT_TYPE).value(), StandardCharsets.UTF_8);
                if (EventType.ORDER_PAID.equals(type) && record.key().equals(s.path("relay").path("orderNo").asText())) {
                    originalPayload = record.value();
                    original = JSON.readValue(originalPayload, OrderPaidEvent.class);
                    assertThat(new String(record.headers().lastHeader(EventHeaders.EVENT_ID).value(), StandardCharsets.UTF_8)).isEqualTo(original.eventId());
                }
            }
        }
        assertThat(original).isNotNull();
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer"); config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            for (int i = 0; i < 2; i++) {
                ProducerRecord<String, String> record = new ProducerRecord<>(original.topic(), original.partitionKey(), originalPayload);
                record.headers().add(EventHeaders.EVENT_ID, original.eventId().getBytes(StandardCharsets.UTF_8)); record.headers().add(EventHeaders.EVENT_TYPE, original.eventType().getBytes(StandardCharsets.UTF_8));
                producer.send(record).get(15, TimeUnit.SECONDS);
            }
        }
        awaitOriginalReplaysConsumed();
        s.put("replayedEventId", original.eventId()); s.set("replayedEvent", JSON.readTree(originalPayload)); save(s);
    }
    static void awaitOriginalReplaysConsumed() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", System.getProperty("stove.e2e.kafka-brokers")))) {
            TopicDescription topic = admin.describeTopics(List.of(Topics.ORDER)).allTopicNames().get(15, TimeUnit.SECONDS).get(Topics.ORDER);
            HashMap<TopicPartition, OffsetSpec> latest = new HashMap<>();
            for (TopicPartitionInfo partition : topic.partitions()) latest.put(new TopicPartition(Topics.ORDER, partition.partition()), OffsetSpec.latest());
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = admin.listOffsets(latest).all().get(15, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets("license-paid-order").partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> end : ends.entrySet()) {
                    assertThat(offsets.get(end.getKey())).isNotNull();
                    assertThat(offsets.get(end.getKey()).offset()).isGreaterThanOrEqualTo(end.getValue().offset());
                }
            });
        }
    }
    @Test void ownershipSecurityAndStableRightsAfterRestart() throws Exception {
        ObjectNode s = saved(); stableLibrary(s); Account buyer = buyer(s); Account other = other(s);
        assertThat(library(s, other)).isEqualTo(s.path("otherLicense"));
        for (String name : List.of("offline", "beforeAck", "relay"))
            assertThat(library(s, buyer(s, name))).isEqualTo(s.path(name + "License"));
        assertThat(library(s, buyer(s, "declined"))).isEqualTo(s.path("retryLicense"));
        assertThat(Stove.gateway.get("/api/v1/library").status()).isEqualTo(401);
        Account reviewer = login(REVIEWER_EMAIL, REVIEWER_PASSWORD);
        assertThat(Stove.gateway.get("/api/v1/library", reviewer.headers()).status()).isEqualTo(403);
        for (String path : List.of("/api/v1/library", "/api/v1/library/" + code(s) + "/revoke", "/api/v1/ops/dlt"))
            assertThat(Stove.gateway.post(path, null, buyer.headers()).status()).isEqualTo(403);
        for (String query : List.of("page=-1", "size=101")) assertThat(Stove.gateway.get("/api/v1/library?" + query, buyer.headers()).status()).isEqualTo(400);
        E2eClient.Response docs = Stove.gateway.get("/v3/api-docs/license"); assertThat(docs.status()).isEqualTo(200);
        assertThat(docs.body().path("paths").size()).isEqualTo(2);
    }
}
