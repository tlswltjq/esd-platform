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
import com.stove.common.contract.EventHeaders;
import com.stove.common.contract.EventType;
import com.stove.common.contract.Topics;
import com.stove.common.contract.payload.OrderPaidEvent;
import com.stove.common.contract.payload.RefundPaymentConfirmedEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("license")
@ExtendWith(LocalStackExtension.class)
@LocalStackApps({StackApp.AUTH, StackApp.STUDIO, StackApp.WEB_BFF, StackApp.GATEWAY, StackApp.REVIEW, StackApp.CATALOG,
        StackApp.STORE, StackApp.ORDER, StackApp.PAYMENT, StackApp.LICENSE})
class LicensePurchaseFlowTest {
    /** 앞 걸음이 만든 주문·보유권. 예전에는 실행기가 걸음마다 테스트를 따로 불러 파일로 넘겼다. */
    private static String state;
    static ObjectNode saved() throws Exception { return (ObjectNode) JSON.readTree(state); }
    static void save(ObjectNode s) throws Exception { state = JSON.writeValueAsString(s); }
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
    private static void preparePendingPurchase() throws Exception {
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
    private static void approvedOrderGrantsOneProduct() throws Exception {
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
    private static void declinedOrderDoesNotAddAPurchase() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "declined"); create(s, "declined", buyer); state(s, "declined", buyer, "FAILED");
        assertThat(Stove.gateway.get("/api/v1/library/" + code(s), buyer.headers()).status()).isEqualTo(404); stableLibrary(s);
    }
    private static void rejectsRepurchaseAndRetriesAFailedOrder() throws Exception {
        ObjectNode s = saved();
        Account buyer = buyer(s, "declined"); create(s, "retry", buyer); state(s, "retry", buyer, "PAID");
        assertThat(s.path("retry").path("orderNo")).isNotEqualTo(s.path("declined").path("orderNo"));
        assertThat(get("orders", s.path("declined").path("orderNo").asText(), buyer).path("status").asText()).isEqualTo("FAILED");
        s.set("retryLicense", library(s, buyer)); save(s); stableLibrary(s);
    }
    private static void anotherBuyerGetsASeparateRight() throws Exception {
        ObjectNode s = saved(); Account other = other(s); create(s, "other", other); state(s, "other", other, "PAID");
        JsonNode license = library(s, other); assertThat(license.path("licenseId")).isNotEqualTo(s.path("firstLicense").path("licenseId"));
        s.set("otherLicense", license); save(s); stableLibrary(s);
    }
    private static void payWhileLicenseIsStopped() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "offline"); create(s, "offline", buyer); state(s, "offline", buyer, "PAID");
    }
    private static void catchesUpAfterLicenseRestart() throws Exception {
        ObjectNode s = saved(); s.set("offlineLicense", library(s, buyer(s, "offline"))); save(s); stableLibrary(s);
    }
    private static void grantCommitsBeforeTheConsumerProcessExits() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "beforeAck"); create(s, "beforeAck", buyer); state(s, "beforeAck", buyer, "PAID");
    }
    private static void redeliveryAfterUnacknowledgedCommitPreservesTheRight() throws Exception {
        ObjectNode s = saved(); s.set("beforeAckLicense", library(s, buyer(s, "beforeAck"))); save(s); stableLibrary(s);
    }
    private static void preparePaymentBeforeOrderRelayPause() throws Exception {
        ObjectNode s = saved(); Account buyer = newBuyer(s, "relay"); create(s, "relay", buyer); state(s, "relay", buyer, "PENDING"); stableLibrary(s);
    }
    private static void readsExistingLibraryWhileCatalogAndBrokerAreStopped() throws Exception { stableLibrary(saved()); }
    private static void catchesUpOriginalPaidOutboxAndReplaysIt() throws Exception {
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
    private static void refundStartsForARegisteredPurchase() throws Exception {
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
    private static void repurchaseAfterRefundCompletes() throws Exception {
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

    private static void ownershipSecurityAndStableRightsAfterRestart() throws Exception {
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

    private static final String PAID_GROUP = "license-paid-order";

    /** 조건이 될 때까지 기다린다. 이벤트가 건너오는 시간을 준다. */
    private static void check(LocalStack stack, String query, Object expected, String description) {
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(stack.sql(query)).as(description).isEqualTo(String.valueOf(expected)));
    }

    private static List<String> orderNumbers(List<String> names) throws Exception {
        ObjectNode s = saved();
        List<String> numbers = new ArrayList<>();
        for (String name : names) {
            numbers.add(s.path(name).path("orderNo").asText());
        }
        return numbers;
    }

    /**
     * 결정 106: 건수는 걸음이 남긴 주문 이름으로 센다. 전체 합계는 지금까지 지급한 이름 수에서 나온다. 지급한 주문마다
     * 구매 근거·Inbox 표시·지급 확인 이벤트가 하나씩이고, 아직 지급하지 않은 주문에는 구매 근거가 없다.
     */
    private static void expectRights(LocalStack stack, List<String> grantedSoFar, List<String> granted, List<String> pending)
            throws Exception {
        for (String number : orderNumbers(granted)) {
            check(stack, "SELECT COUNT(*) FROM stove_license.product_license WHERE order_no='" + number + "'", 1,
                    "구매 근거 하나: " + number);
            check(stack, "SELECT COUNT(*) FROM stove_license.processed_event p JOIN stove_license.product_license l "
                    + "ON l.event_id=p.event_id WHERE p.consumer_group='" + PAID_GROUP + "' AND l.order_no='" + number + "'",
                    1, "Inbox 표시 하나: " + number);
            check(stack, "SELECT COUNT(*) FROM stove_license.outbox_event e JOIN stove_license.product_license l "
                    + "ON e.aggregate_id=l.license_id WHERE e.event_type='LicenseGranted' AND l.order_no='" + number + "'",
                    1, "지급 확인 하나: " + number);
        }
        for (String number : orderNumbers(pending)) {
            check(stack, "SELECT COUNT(*) FROM stove_license.product_license WHERE order_no='" + number + "'", 0,
                    "아직 구매 근거 없음: " + number);
        }
        for (String name : granted) {
            if (!grantedSoFar.contains(name)) {
                grantedSoFar.add(name);
            }
        }
        int total = grantedSoFar.size();
        check(stack, "SELECT COUNT(*) FROM stove_license.product_license", total, "지급한 주문의 구매 근거만: " + total);
        check(stack, "SELECT COUNT(*) FROM stove_license.processed_event WHERE consumer_group='" + PAID_GROUP + "'", total,
                "License Inbox: " + total);
        check(stack, "SELECT COUNT(*) FROM stove_license.outbox_event WHERE event_type='LicenseGranted'", total,
                "지급 확인 이벤트: " + total);
        check(stack, "SELECT COUNT(*) FROM stove_license.license_purchase", 0, "옛 구매 표에는 쓰지 않는다");
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_license.outbox_event WHERE event_type<>'LicenseGranted'"))
                .isEqualTo("0");
    }

    private static void expectPaid(LocalStack stack, String... names) throws Exception {
        StringBuilder listed = new StringBuilder();
        for (String number : orderNumbers(List.of(names))) {
            listed.append(listed.length() == 0 ? "" : ",").append("'").append(number).append("'");
        }
        check(stack, "SELECT COUNT(*) FROM stove_order.purchase_order WHERE status='PAID' AND order_no IN (" + listed + ")",
                names.length, "결제된 주문: " + String.join(", ", names));
    }

    /**
     * 승인·거절·대기, 재구매와 실패 재시도, 구매자별 보유권, License 정지 뒤 따라잡기, 커밋 뒤 ACK 전 종료와 재전달,
     * 브로커·Catalog 정지, Order Outbox 재시작과 원본 재전달, 환불 완료 뒤 재구매·등록을 한 흐름으로 본다. 테스트가 앱과
     * 브로커를 직접 멈추고 되살리며, 거절과 처리 도중 종료는 검증 전용 장애 설정으로 만든다(결정 e2e-stack-in-junit).
     */
    @Test
    void rightsAreGrantedOnceAndSurviveFaultsOutagesRefundsAndRestarts(LocalStack stack) throws Exception {
        List<String> granted = new ArrayList<>();
        List<String> none = List.of();

        // 처리를 끈 Payment: 접수만 한다.
        stack.restart(StackApp.PAYMENT, "--stove.payment.processing-enabled=false");
        preparePendingPurchase();
        expectRights(stack, granted, none, List.of("approved"));
        stack.restart(StackApp.PAYMENT);
        approvedOrderGrantsOneProduct();
        expectRights(stack, granted, List.of("approved", "repurchase"), none);
        String firstRight = "SELECT CONCAT(license_id,'|',acquired_at) FROM stove_license.product_license ORDER BY id LIMIT 1";
        String first = stack.sql(firstRight);

        PaymentFault.restart(stack, PaymentFault.DECLINE);
        declinedOrderDoesNotAddAPurchase();
        expectRights(stack, granted, none, List.of("declined"));
        stack.restart(StackApp.PAYMENT);
        rejectsRepurchaseAndRetriesAFailedOrder();
        expectRights(stack, granted, List.of("retry"), none);
        anotherBuyerGetsASeparateRight();
        expectRights(stack, granted, List.of("other"), none);

        // License가 내려가 있는 동안 결제된 주문은 다시 뜬 뒤 지급된다.
        stack.stop(StackApp.LICENSE);
        payWhileLicenseIsStopped();
        expectPaid(stack, "offline");
        expectRights(stack, granted, none, List.of("offline"));
        stack.start(StackApp.LICENSE);
        expectRights(stack, granted, List.of("offline"), none);
        catchesUpAfterLicenseRestart();

        // 지급을 커밋한 뒤 ACK 전에 프로세스가 내려간다. 같은 OrderPaid가 다시 와도 구매 근거는 그대로다.
        Path marker = stack.directory().resolve("after-commit");
        LicenseFault.restart(stack, LicenseFault.commitMarker(marker), LicenseFault.HALT_AFTER_COMMIT);
        grantCommitsBeforeTheConsumerProcessExits();
        Awaitility.await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(Files.exists(marker)).as("지급 커밋 표시").isTrue());
        assertThat(stack.awaitExit(StackApp.LICENSE, Duration.ofSeconds(30))).as("ACK 전에 내려간 License의 종료 코드")
                .isEqualTo(LicenseFault.HALT_EXIT_CODE);
        String eventId = Files.readString(marker);
        String committedRight = "SELECT CONCAT(id,'|',license_id,'|',event_id,'|',acquired_at,'|',received_at) "
                + "FROM stove_license.product_license WHERE event_id='" + eventId + "'";
        String before = stack.sql(committedRight);
        expectRights(stack, granted, List.of("beforeAck"), none);
        LicenseFault.restart(stack, LicenseFault.commitMarker(marker));
        Path deliveries = Path.of(marker + ".deliveries");
        Awaitility.await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(Files.readAllLines(deliveries).stream().filter(eventId::equals).count())
                        .as("ACK되지 않은 커밋 뒤 원본 OrderPaid 재전달").isGreaterThanOrEqualTo(2));
        redeliveryAfterUnacknowledgedCommitPreservesTheRight();
        assertThat(stack.sql(committedRight)).as("재전달 뒤에도 같은 구매 근거").isEqualTo(before);
        expectRights(stack, granted, none, none);
        stack.restart(StackApp.LICENSE);

        // 릴레이를 끈 Order에 OrderPaid가 남아 있고, Catalog와 브로커가 멈춰도 라이브러리는 읽힌다.
        stack.restart(StackApp.PAYMENT, "--stove.payment.processing-enabled=false");
        preparePaymentBeforeOrderRelayPause();
        stack.restart(StackApp.ORDER, "--stove.outbox.relay-enabled=false");
        stack.restart(StackApp.PAYMENT);
        expectPaid(stack, "relay");
        check(stack, "SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid' AND status='PENDING'", 1,
                "릴레이를 끈 동안 남은 원본 OrderPaid");
        String pendingPaid = "SELECT CONCAT(event_id,'|',payload) FROM stove_order.outbox_event "
                + "WHERE event_type='OrderPaid' AND status='PENDING'";
        String original = stack.sql(pendingPaid);
        expectRights(stack, granted, none, List.of("relay"));
        stack.stop(StackApp.CATALOG);
        stack.stop(StackApp.ORDER);
        stack.pauseKafka();
        try {
            stack.start(StackApp.ORDER, "--stove.outbox.relay-enabled=false");
            readsExistingLibraryWhileCatalogAndBrokerAreStopped();
            assertThat(stack.sql(pendingPaid)).as("같은 OrderPaid가 그대로 남아 있다").isEqualTo(original);
        } finally {
            stack.resumeKafka();
        }
        stack.restart(StackApp.ORDER);
        expectRights(stack, granted, List.of("relay"), none);
        catchesUpOriginalPaidOutboxAndReplaysIt();
        ObjectNode replay = saved();
        String originalId = original.split("\\|", 2)[0];
        assertThat(replay.path("replayedEventId").asText()).isEqualTo(originalId);
        assertThat(JSON.readTree(stack.sql("SELECT payload FROM stove_order.outbox_event WHERE event_id='" + originalId + "'")))
                .as("재전달한 이벤트는 Outbox의 원본과 같다").isEqualTo(replay.path("replayedEvent"));
        check(stack, "SELECT status FROM stove_order.outbox_event WHERE event_id='" + originalId + "'", "SENT",
                "원본 OrderPaid가 나갔다");
        stack.restart(StackApp.LICENSE);
        ownershipSecurityAndStableRightsAfterRestart();
        expectRights(stack, granted, none, none);
        assertThat(stack.sql(firstRight)).as("첫 구매 근거는 처음 그대로다").isEqualTo(first);

        // 결정 106: 결제한 주문은 지급한 주문 전부, 결제를 시도한 주문은 거기에 거절된 주문을 더한 것이다.
        String charged = Integer.toString(granted.size() + orderNumbers(List.of("declined")).size());
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_payment.simulated_payment_charge")).isEqualTo("0");
        // 다시 온 OrderPaid가 결제를 새로 만들지 않는다.
        for (String table : List.of("purchase_payment", "processed_event", "outbox_event")) {
            assertThat(stack.sql("SELECT COUNT(*) FROM stove_payment." + table)).as(table).isEqualTo(charged);
        }
        String grantedCount = Integer.toString(granted.size());
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='OrderPaid'"))
                .isEqualTo(grantedCount);
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_license.product_license l "
                + "JOIN stove_order.purchase_order o ON l.order_no=o.order_no "
                + "JOIN stove_order.outbox_event e ON e.event_type='OrderPaid' AND e.event_id=l.event_id AND e.aggregate_id=o.order_no "
                + "WHERE o.status='PAID' AND l.occurred_at=o.updated_at AND l.request_event_id=o.request_event_id "
                + "AND l.payment_result_event_id=o.payment_result_event_id AND l.payment_id=o.payment_id "
                + "AND l.buyer_subject=o.buyer_subject AND l.product_id=o.product_id AND l.product_code=o.product_code "
                + "AND l.title=o.title AND l.content_version=o.content_version AND l.amount=o.amount AND l.currency=o.currency"))
                .as("구매 근거는 결제된 주문과 그 OrderPaid의 값 그대로다").isEqualTo(grantedCount);
        assertThat(stack.sql("SELECT COUNT(*) FROM stove_license.product_license WHERE order_no="
                + "(SELECT order_no FROM stove_order.purchase_order WHERE status='FAILED')")).isEqualTo("0");

        // 결정 93~97·100: 등록 → 자동 환불 → PG 결과만 주입 → 환불 완료 → License 종료 → 같은 상품 재구매·등록.
        // 이 스택의 Payment는 실행 요청을 받기만 하고 PG 취소를 보내지 않는다. 새 주문은 Catalog 공개 상품을 확인하므로
        // 앞에서 멈춘 Catalog를 다시 띄운다.
        stack.start(StackApp.CATALOG);
        refundStartsForARegisteredPurchase();
        ObjectNode current = saved();
        String refundOrder = current.path("refund").path("orderNo").asText();
        check(stack, "SELECT status FROM stove_order.order_refund WHERE order_no='" + refundOrder + "'",
                "PENDING_PAYMENT_REFUND", "환불이 PG 결과를 기다린다");
        check(stack, "SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='RefundExecutionRequested' "
                + "AND aggregate_id='" + refundOrder + "' AND status='SENT'", 1, "환불 실행 요청이 나갔다");
        check(stack, "SELECT status FROM stove_license.product_license WHERE order_no='" + refundOrder + "'", "SUSPENDED",
                "환불 중인 구매는 정지된다");
        String[] fields = stack.sql("SELECT CONCAT_WS('|',r.refund_id,r.current_attempt_id,a.execution_request_event_id,"
                + "o.payment_id,o.buyer_subject,o.amount,o.currency) FROM stove_order.order_refund r "
                + "JOIN stove_order.order_refund_attempt a ON a.attempt_id=r.current_attempt_id "
                + "JOIN stove_order.purchase_order o ON o.order_no=r.order_no WHERE r.order_no='" + refundOrder + "'")
                .split("\\|");
        current.set("refundConfirmation", JSON.createObjectNode().put("orderNo", refundOrder).put("refundId", fields[0])
                .put("attemptId", fields[1]).put("requestEventId", fields[2]).put("paymentId", fields[3])
                .put("buyerSubject", fields[4]).put("amount", Long.parseLong(fields[5])).put("currency", fields[6]));
        save(current);
        repurchaseAfterRefundCompletes();
        String again = saved().path("refundRepurchase").path("orderNo").asText();
        check(stack, "SELECT status FROM stove_order.order_refund WHERE order_no='" + refundOrder + "'", "COMPLETED",
                "환불 완료");
        check(stack, "SELECT COUNT(*) FROM stove_order.outbox_event WHERE event_type='PurchaseRefunded' AND aggregate_id='"
                + refundOrder + "'", 1, "PurchaseRefunded는 하나다");
        check(stack, "SELECT status FROM stove_license.product_license WHERE order_no='" + refundOrder + "'", "REFUNDED",
                "환불된 구매는 끝났다");
        check(stack, "SELECT GROUP_CONCAT(status) FROM stove_license.license_registration WHERE order_no='" + refundOrder
                + "'", "ENDED", "환불된 등록은 끝났다");
        check(stack, "SELECT status FROM stove_license.product_license WHERE order_no='" + again + "'", "REGISTERED",
                "재구매분이 등록됐다");
        check(stack, "SELECT COUNT(*) FROM stove_license.license_registration WHERE status='ACTIVE' AND buyer_subject="
                + "(SELECT buyer_subject FROM stove_order.purchase_order WHERE order_no='" + again + "')", 1,
                "재구매 뒤 쓸 수 있는 보유권은 하나다");
        check(stack, "SELECT status FROM stove_order.purchase_order WHERE order_no='" + again + "'", "PAID", "재구매 결제");
    }
}
