package com.stove.e2e;

import static com.stove.e2e.DownloadPurchaseFlowTest.approveAndPublish;
import static com.stove.e2e.DownloadPurchaseFlowTest.verifyZip;
import static com.stove.e2e.ReviewJourney.ADMIN_EMAIL;
import static com.stove.e2e.ReviewJourney.ADMIN_PASSWORD;
import static com.stove.e2e.ReviewJourney.JSON;
import static com.stove.e2e.ReviewJourney.account;
import static com.stove.e2e.ReviewJourney.data;
import static com.stove.e2e.ReviewJourney.login;
import static com.stove.e2e.ReviewJourney.page;
import static com.stove.e2e.ReviewJourney.project;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.kafka.EventHeaders;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.Topics;
import com.stove.e2e.ReviewJourney.Account;
import com.stove.e2e.ReviewJourney.Project;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("settlement")
class SettlementPurchaseFlowTest {
    static final String BASE="/api/v1/settlements";
    static Path evidence() { return Path.of(System.getProperty("stove.e2e.settlement-evidence")); }
    static ObjectNode saved() throws Exception { return (ObjectNode)JSON.readTree(Files.readString(evidence())); }
    static void save(ObjectNode state) throws Exception {
        Files.writeString(evidence(),JSON.writeValueAsString(state));Files.setPosixFilePermissions(evidence(),PosixFilePermissions.fromString("rw-------"));
    }
    static Account admin() throws Exception { return login(ADMIN_EMAIL,ADMIN_PASSWORD); }
    static Account buyer(ObjectNode state) throws Exception { return login(state.path("latestBuyerEmail").asText(),"creator-password-1234"); }
    static JsonNode sales(Account account) { return Stove.gateway.get(BASE+"/sales?size=100",account.headers()).data(); }
    static JsonNode buy(JsonNode product,Account buyer,String expected) {
        E2eClient.Response response=Stove.gateway.post("/api/v1/orders",Map.of("requestId",UUID.randomUUID().toString(),
                "productCode",product.path("productCode").asText(),"expectedContentVersion",product.path("contentVersion").asLong(),
                "expectedAmount",product.path("price").asLong()),buyer.headers());
        assertThat(response.status()).isEqualTo(200);String order=response.data().path("orderNo").asText();
        waitStatus(order,buyer,expected);return response.data();
    }
    static void waitStatus(String order,Account buyer,String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/orders/"+order,buyer.headers()).data().path("status").asText()).isEqualTo(expected));
    }
    static void verifyDownload(JsonNode product,Account buyer,byte[] zip) throws Exception {
        String code=product.path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(Stove.gateway.get("/api/v1/library/"+code,buyer.headers()).status()).isEqualTo(200));
        E2eClient.Response ticket=Stove.gateway.get("/api/v1/downloads/"+code+"/ticket",buyer.headers());
        assertThat(ticket.status()).isEqualTo(200);verifyZip(ticket.data(),zip);
    }
    static void newPurchase(ObjectNode state,String expected) throws Exception {
        Account buyer=account(false);JsonNode order=buy(state.path("product"),buyer,expected);
        state.put("latestBuyerEmail",buyer.email()).put("latestOrderNo",order.path("orderNo").asText());save(state);
    }
    static JsonNode collect(ObjectNode state,Account admin) {
        E2eClient.Response response=Stove.gateway.post(BASE+"/pg-receipts",Map.of("providerSettlementId",state.path("activeReceiptId").asText()),admin.headers());
        assertThat(response.status()).isEqualTo(200);return response.data();
    }

    @Test void initialPurchasesAndDownloads() throws Exception {
        ObjectNode state=JSON.createObjectNode();ArrayNode creators=state.putArray("creators");ArrayNode orders=state.putArray("initialOrders");
        ArrayNode products=state.putArray("products");ArrayNode zips=state.putArray("zips");
        for(int i=0;i<2;i++) {
            Account creator=account(true);Project project=project(creator);page(project.id(),creator,"Settlement product "+i,10000);
            approveAndPublish(project.id(),creator);String code=data(project.id(),creator).path("productCode").asText();
            Awaitility.await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/storefront/products/"+code).status()).isEqualTo(200));
            creators.addObject().put("email",creator.email()).put("subject",creator.subject());
            products.add(Stove.gateway.get("/api/v1/products/by-code/"+code).data());zips.add(Base64.getEncoder().encodeToString(project.zip()));
        }
        for(int index:new int[]{0,1,0}) {
            Account buyer=account(false);JsonNode product=products.get(index);JsonNode order=buy(product,buyer,"PAID");
            verifyDownload(product,buyer,Base64.getDecoder().decode(zips.get(index).asText()));
            orders.add(order.path("orderNo").asText());
        }
        state.set("product",products.get(0));state.put("expectedSales",3);save(state);
        Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(sales(admin()).path("totalElements").asInt()).isEqualTo(3));
    }
    @Test void allocationAndOwnershipAreIsolated() throws Exception {
        ObjectNode state=saved();Account admin=admin();JsonNode all=sales(admin);assertThat(all.path("totalElements").asInt()).isEqualTo(3);
        for(JsonNode sale:all.path("content")) {
            assertThat(sale.path("sale").path("amount").asLong()).isEqualTo(10000);
            assertThat(sale.path("sale").path("sellerPayoutAmount").asLong()).isEqualTo(9000);
            assertThat(sale.path("pgCost").path("pgFeeAmount").asLong()).isEqualTo(300);
            assertThat(sale.path("platformNetAmount").asLong()).isEqualTo(700);
        }
        for(int index=0;index<2;index++) {
            Account creator=login(state.path("creators").get(index).path("email").asText(),"creator-password-1234");
            E2eClient.Response own=Stove.gateway.get(BASE+"/me/sales?sellerSubject=forged",creator.headers());
            assertThat(own.data().path("totalElements").asInt()).isEqualTo(index==0?2:1);
            assertThat(own.body().toString()).doesNotContain("pgCost","platformNetAmount","buyerSubject");
            assertThat(Stove.gateway.get(BASE+"/sales",creator.headers()).status()).isEqualTo(403);
            assertThat(Stove.gateway.post(BASE+"/pg-receipts",Map.of("providerSettlementId","forged"),creator.headers()).status()).isEqualTo(403);
        }
        assertThat(Stove.gateway.get(BASE+"/sales").status()).isEqualTo(401);
        assertThat(Stove.gateway.post(BASE+"/pg-receipts",Map.of("providerSettlementId","forged","payoutAmount",1),admin.headers()).status()).isEqualTo(400);
        String month=YearMonth.now(ZoneId.of("Asia/Seoul")).toString();
        assertThat(Stove.gateway.post(BASE+"/sellers/"+state.path("creators").get(0).path("subject").asText()+"/months/"+month+"/close",null,admin.headers()).status()).isEqualTo(409);
    }
    @Test void collectsPayoutAndRetriesWithoutDuplicatingAmounts() throws Exception {
        ObjectNode state=saved();Account admin=admin();JsonNode first=collect(state,admin);
        assertThat(first.path("status").asText()).isEqualTo("SIMULATED_RECEIVED");
        assertThat(first.path("statement").path("recordCount").asLong()).isEqualTo(3);
        assertThat(first.path("statement").path("payoutAmount").asLong()).isEqualTo(29100);
        assertThat(collect(state,admin).path("simulatedReceivedAt")).isEqualTo(first.path("simulatedReceivedAt"));
        state.put("firstReceiptId",state.path("activeReceiptId").asText());state.set("firstReceivedAt",first.path("simulatedReceivedAt"));save(state);
    }
    @Test void storedReadsAndReconciliationSurviveExternalOutage() throws Exception {
        ObjectNode state=saved();Account admin=admin();
        assertThat(sales(admin).path("totalElements").asInt()).isEqualTo(state.path("expectedSales").asInt());
        JsonNode receipt=Stove.gateway.post(BASE+"/pg-receipts/"+state.path("firstReceiptId").asText()+"/reconcile",null,admin.headers()).data();
        assertThat(receipt.path("simulatedReceivedAt")).isEqualTo(state.path("firstReceivedAt"));
        assertThat(Stove.gateway.post(BASE+"/pg-receipts",Map.of("providerSettlementId",UUID.randomUUID().toString()),admin.headers()).status()).isEqualTo(503);
        assertThat(Stove.gateway.post(BASE+"/sellers/empty-seller/months/2026-09/close",null,admin.headers()).data().path("month").path("status").asText()).isEqualTo("CLOSED");
    }
    @Test void paidPurchaseWhileSettlementIsStopped() throws Exception {
        ObjectNode state=saved();newPurchase(state,"PAID");verifyDownload(state.path("product"),buyer(state),Base64.getDecoder().decode(state.path("zips").get(0).asText()));
    }
    @Test void latestSaleArrivesExactlyOnce() throws Exception {
        ObjectNode state=saved();Account admin=admin();
        Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            JsonNode all=sales(admin);assertThat(all.path("totalElements").asInt()).isEqualTo(state.path("expectedSales").asInt());
            long matches=0;for(JsonNode sale:all.path("content")) if(sale.path("sale").path("orderNo").asText().equals(state.path("latestOrderNo").asText())) matches++;
            assertThat(matches).isEqualTo(1);
        });
    }
    @Test void preparesPendingPurchase() throws Exception {
        ObjectNode state=saved();newPurchase(state,"PENDING");Account buyer=buyer(state);
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/payments/"+state.path("latestOrderNo").asText(),buyer.headers()).data().path("status").asText()).isEqualTo("PENDING"));
    }
    @Test void pendingPurchaseBecomesPaid() throws Exception {
        ObjectNode state=saved();waitStatus(state.path("latestOrderNo").asText(),buyer(state),"PAID");
    }
    @Test void payoutBeforeOrderPaidWaitsForMissingSale() throws Exception {
        ObjectNode state=saved();JsonNode receipt=collect(state,admin());
        assertThat(receipt.path("status").asText()).isEqualTo("REPORTED_PAID");
        assertThat(receipt.path("statement").path("recordCount").asLong()).isEqualTo(2);
        assertThat(receipt.path("simulatedReceivedAt").isNull()).isTrue();
    }
    @Test void storedPayoutReconcilesAfterDelayedSale() throws Exception {
        ObjectNode state=saved();Account admin=admin();String id=state.path("activeReceiptId").asText();
        JsonNode receipt=Stove.gateway.post(BASE+"/pg-receipts/"+id+"/reconcile",null,admin.headers()).data();
        assertThat(receipt.path("status").asText()).isEqualTo("SIMULATED_RECEIVED");
        assertThat(receipt.path("statement").path("payoutAmount").asLong()).isEqualTo(19400);
        assertThat(Stove.gateway.post(BASE+"/pg-receipts/"+id+"/reconcile",null,admin.headers()).data().path("simulatedReceivedAt")).isEqualTo(receipt.path("simulatedReceivedAt"));
    }
    @Test void providerFailureReachesDltWithOriginalPaidEvidence() throws Exception {
        ObjectNode state=saved();Map<String,Object> configuration=new HashMap<String,Object>();
        configuration.put("bootstrap.servers",System.getProperty("stove.e2e.kafka-brokers"));configuration.put("group.id","settlement-evidence-"+UUID.randomUUID());
        configuration.put("auto.offset.reset","earliest");configuration.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        configuration.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        try(KafkaConsumer<String,String> consumer=new KafkaConsumer<String,String>(configuration)) {
            consumer.subscribe(List.of(Topics.ORDER+".DLT"));boolean found=false;long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(!found && System.nanoTime()<deadline) for(ConsumerRecord<String,String> failed:consumer.poll(Duration.ofMillis(500))) {
                JsonNode payload=JSON.readTree(failed.value());
                if(payload.path("eventId").asText().equals(state.path("replayEvent").path("eventId").asText())) {
                    assertThat(payload).isEqualTo(state.path("replayEvent"));found=true;
                }
            }
            assertThat(found).isTrue();
        }
    }
    @Test void replaysOriginalOrderPaidAfterProviderRecovery() throws Exception {
        ObjectNode state=saved();JsonNode event=state.path("replayEvent");
        Map<String,Object> configuration=new HashMap<String,Object>();configuration.put("bootstrap.servers",System.getProperty("stove.e2e.kafka-brokers"));
        configuration.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");configuration.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");
        try(KafkaProducer<String,String> producer=new KafkaProducer<String,String>(configuration)) {
            ProducerRecord<String,String> replay=new ProducerRecord<String,String>(Topics.ORDER,event.path("orderNo").asText(),JSON.writeValueAsString(event));
            replay.headers().add(EventHeaders.EVENT_ID,event.path("eventId").asText().getBytes(StandardCharsets.UTF_8));
            replay.headers().add(EventHeaders.EVENT_TYPE,EventType.ORDER_PAID.getBytes(StandardCharsets.UTF_8));
            producer.send(replay).get(15,TimeUnit.SECONDS);producer.send(replay).get(15,TimeUnit.SECONDS);
        }
    }
    @Test void declinedPurchaseDoesNotCreateASale() throws Exception { newPurchase(saved(),"FAILED"); }
    @Test void allReceiptsAndAllocationsAgree() throws Exception {
        ObjectNode state=saved();Account admin=admin();JsonNode receipt=collect(state,admin());
        assertThat(receipt.path("status").asText()).isEqualTo("SIMULATED_RECEIVED");
        JsonNode rows=Stove.gateway.get(BASE+"/pg-receipts?size=100",admin.headers()).data();long received=0;
        for(JsonNode row:rows.path("content")) {
            assertThat(row.path("status").asText()).isEqualTo("SIMULATED_RECEIVED");received+=row.path("statement").path("payoutAmount").asLong();
        }
        assertThat(received).isEqualTo(67900);assertThat(sales(admin).path("totalElements").asLong()).isEqualTo(7);
    }
}
