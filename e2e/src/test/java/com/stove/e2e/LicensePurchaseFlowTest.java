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
import static com.stove.e2e.ReviewJourney.registeredLibrary;
import static com.stove.e2e.ReviewJourney.repurchaseStaysInStorage;
import static com.stove.e2e.ReviewJourney.storageItem;
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
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    /** 결정 93: 결제한 구매자가 보관함 항목을 등록해야 라이브러리에 생긴다. */
    static JsonNode library(ObjectNode s, Account account) {
        return registeredLibrary(account, code(s));
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
        assertThat(Stove.gateway.post(path(project.id()) + "/release-requests", ReviewJourney.submission(path(project.id()), creator.headers()), creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(data(project.id(), creator).path("currentReleaseRequest").path("status").asText()).isEqualTo("COMPLETED"));
        String code = data(project.id(), creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/storefront/products/" + code).status()).isEqualTo(200));
        ObjectNode s = JSON.createObjectNode().put("buyerEmail", buyer.email()).put("otherEmail", other.email());
        s.set("product", Stove.gateway.get("/api/v1/storefront/products/" + code).data());
        create(s, "approved", buyer); state(s, "approved", buyer, "PENDING");
        // 결정 89: 진행 중 주문이 있어도 새 주문은 막지 않는다. 재구매 수락은 approvedOrderGrantsOneProduct에서 확인한다.
        assertThat(Stove.gateway.get("/api/v1/library", buyer.headers()).data().path("totalElements").asInt()).isZero();
        assertThat(Stove.gateway.get("/api/v1/library/" + code, buyer.headers()).status()).isEqualTo(404); save(s);
    }
    @Test void approvedOrderGrantsOneProduct() throws Exception {
        ObjectNode s = saved(); Account buyer = buyer(s); state(s, "approved", buyer, "PAID");
        JsonNode license = library(s, buyer); assertThat(license.path("productId")).isEqualTo(s.path("product").path("productId"));
        assertThat(license.path("title")).isEqualTo(s.path("product").path("title")); assertThat(license.path("licenseId").asText()).isNotBlank();
        s.set("firstLicense", license); save(s); stableLibrary(s);
        // 결정 89·93: 이미 가진 상품도 다시 살 수 있고, 새 구매분은 보관함에 남으며 등록은 409다.
        JsonNode repurchase = create(s, "repurchase", buyer);
        assertThat(repurchase.path("orderNo")).isNotEqualTo(s.path("approved").path("orderNo"));
        state(s, "repurchase", buyer, "PAID");
        repurchaseStaysInStorage(buyer, code(s), repurchase.path("orderNo").asText());
        stableLibrary(s);
    }
    @Test void declinedOrderDoesNotAddAPurchase() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "declined"); create(s, "declined", buyer); state(s, "declined", buyer, "FAILED");
        assertThat(Stove.gateway.get("/api/v1/library/" + code(s), buyer.headers()).status()).isEqualTo(404); stableLibrary(s);
    }
    @Test void rejectsRepurchaseAndRetriesAFailedOrder() throws Exception {
        ObjectNode s = saved();
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
    /**
     * 결정 93·94: 등록한 구매를 4시간 안에 환불 요청하면 자동 승인되고 License가 구매 근거·보유권을 정지한다.
     * 격리 스택의 Payment는 PortOne 자격이 없어 실행 요청을 받기만 하고 PG 취소를 보내지 않는다. 다음 단계에서 PG 결과만 넣는다.
     */
    @Test void refundStartsForARegisteredPurchase() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "refund"); create(s, "refund", buyer); state(s, "refund", buyer, "PAID");
        JsonNode registered = library(s, buyer); s.set("refundLicense", registered); save(s);
        String no = s.path("refund").path("orderNo").asText();
        E2eClient.Response requested = Stove.gateway.post("/api/v1/orders/" + no + "/refund", null, buyer.headers());
        assertThat(requested.status()).as(requested.toString()).isBetween(200, 202);
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(get("orders", no + "/refund", buyer).path("status").asText()).isEqualTo("PROCESSING");
            assertThat(get("library", code(s), buyer).path("status").asText()).isEqualTo("SUSPENDED");
        });
    }

    /**
     * 결정 96·97·89: PG 전액 취소 결과(RefundPaymentConfirmed)를 Payment처럼 Kafka에 넣으면 Order가 환불을 끝내고
     * PurchaseRefunded로 License가 구매를 끝낸다. 라이브러리에서 빠진 뒤 같은 상품을 다시 사서 등록하면 새 보유권이 ACTIVE다.
     */
    @Test void repurchaseAfterRefundCompletes() throws Exception {
        ObjectNode s = saved(); Account buyer = buyer(s, "refund"); JsonNode refund = s.path("refundConfirmation");
        String no = refund.path("orderNo").asText();
        RefundPaymentConfirmedEvent confirmed = new RefundPaymentConfirmedEvent(UUID.randomUUID().toString(),
                Instant.now().truncatedTo(ChronoUnit.MICROS), refund.path("refundId").asText(), refund.path("attemptId").asText(),
                refund.path("requestEventId").asText(), no, refund.path("paymentId").asText(), refund.path("buyerSubject").asText(),
                refund.path("amount").asLong(), refund.path("currency").asText(), "PG_QUERY_UNATTRIBUTED", "e2e-pg-" + no,
                List.of("e2e-cancel-" + refund.path("refundId").asText()));
        HashMap<String, Object> config = new HashMap<>(); config.put("bootstrap.servers", System.getProperty("stove.e2e.kafka-brokers"));
        config.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer"); config.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(confirmed.topic(), confirmed.partitionKey(), JSON.writeValueAsString(confirmed));
            record.headers().add(EventHeaders.EVENT_ID, confirmed.eventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventHeaders.EVENT_TYPE, confirmed.eventType().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(15, TimeUnit.SECONDS);
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(get("orders", no + "/refund", buyer).path("status").asText()).isEqualTo("COMPLETED");
            assertThat(Stove.gateway.get("/api/v1/library/" + code(s), buyer.headers()).status()).isEqualTo(404);
        });
        assertThat(storageItem(buyer, no).isMissingNode()).as("환불이 끝난 구매는 보관함에서도 빠진다").isTrue();
        assertThat(Stove.gateway.get("/api/v1/library", buyer.headers()).data().path("totalElements").asInt()).isZero();

        create(s, "refundRepurchase", buyer); state(s, "refundRepurchase", buyer, "PAID");
        String again = s.path("refundRepurchase").path("orderNo").asText();
        JsonNode[] item = new JsonNode[1];
        Awaitility.await().atMost(Duration.ofSeconds(50)).untilAsserted(() -> {
            item[0] = storageItem(buyer, again);
            assertThat(item[0].isMissingNode()).as("재구매 보관함 항목").isFalse();
            assertThat(item[0].path("registrable").asBoolean()).isTrue();
        });
        E2eClient.Response registered = Stove.gateway.post("/api/v1/storage/" + item[0].path("licenseId").asText() + "/registration", null, buyer.headers());
        assertThat(registered.status()).as(registered.toString()).isEqualTo(200);
        JsonNode license = get("library", code(s), buyer);
        assertThat(license.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(license.path("licenseId").asText()).isEqualTo(item[0].path("licenseId").asText())
                .isNotEqualTo(s.path("refundLicense").path("licenseId").asText());
        assertThat(get("orders", no + "/refund", buyer).path("status").asText()).isEqualTo("COMPLETED");
        s.set("refundRepurchaseLicense", license); save(s);
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
        // 라이브러리 목록·상세, 보관함 목록, 보관함 등록.
        assertThat(docs.body().path("paths").size()).isEqualTo(4);
    }
}
