package com.stove.e2e;

import static com.stove.e2e.ReviewJourney.Account;
import static com.stove.e2e.ReviewJourney.JSON;
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
import com.stove.common.kafka.contract.DomainEvent;
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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Eight actual apps; process faults are supplied only by the OCI runner's test classpath. */
@Tag("payment")
class PaymentPurchaseFlowTest {
    private static Path evidence() { return Path.of(System.getProperty("stove.e2e.payment-evidence")); }
    private static ObjectNode saved() throws Exception { return (ObjectNode)JSON.readTree(Files.readString(evidence())); }
    private static void save(ObjectNode s) throws Exception { Files.writeString(evidence(),JSON.writeValueAsString(s)); }
    private static Account buyer(JsonNode s) throws Exception { return login(s.path("buyerEmail").asText(),"creator-password-1234"); }
    private static Account buyer(JsonNode s, String name) throws Exception {
        return name.equals("approved") ? buyer(s) : login(s.path(name + "BuyerEmail").asText(), "creator-password-1234");
    }
    private static Account newBuyer(ObjectNode s, String name) throws Exception {
        Account account = account(false); s.put(name + "BuyerEmail", account.email()); save(s); return account;
    }
    private static JsonNode get(String resource,String no,Account buyer) {
        E2eClient.Response r=Stove.gateway.get("/api/v1/"+resource+"/"+no,buyer.headers());
        assertThat(r.status()).as(r.toString()).isEqualTo(200); return r.data();
    }
    private static JsonNode state(String resource,String no,Account buyer,String state) {
        Awaitility.await().atMost(Duration.ofSeconds(50)).untilAsserted(() -> assertThat(get(resource,no,buyer).path("status").asText()).isEqualTo(state));
        return get(resource,no,buyer);
    }
    private static String number(JsonNode s,String name) { return s.path(name).path("orderNo").asText(); }
    private static JsonNode create(ObjectNode s,String name,Account buyer) throws Exception {
        JsonNode product=s.path("product");
        Map<String, Object> input=Map.of("requestId",UUID.randomUUID().toString(),"productCode",product.path("productCode").asText(),
                "expectedContentVersion",product.path("contentVersion").asLong(),"expectedAmount",product.path("price").asLong());
        E2eClient.Response r=Stove.gateway.post("/api/v1/orders",input,buyer.headers());
        assertThat(r.status()).as(r.toString()).isEqualTo(200); s.set(name,r.data()); save(s); return r.data();
    }
    private static void finalState(ObjectNode s,String name,Account buyer,String expected) throws Exception {
        JsonNode payment=state("payments",number(s,name),buyer,expected);
        JsonNode order=state("orders",number(s,name),buyer,expected);
        for(String field:List.of("productId","productCode","title","contentVersion","listAmount","discountAmount","amount","currency"))
            assertThat(payment.path(field)).as(field).isEqualTo(s.path(name).path(field));
        assertThat(payment.path("provider").asText()).isEqualTo("SIMULATED");
        s.set(name+"Payment",payment); s.set(name+"Order",order); save(s);
    }
    @Test void prepareReceiptWithoutProcessing() throws Exception {
        ReviewJourney.Account creator=account(true); ReviewJourney.Account buyer=account(false); ReviewJourney.Account other=account(false);
        ReviewJourney.Account reviewer=login(REVIEWER_EMAIL,REVIEWER_PASSWORD); ReviewJourney.Project project=project(creator);
        long review=request(project.id(),creator,reviewer);
        assertThat(Stove.gateway.post(reviewPath(review)+"/approve",null,reviewer.headers()).status()).isEqualTo(200);
        waitForResult(project.id(),review,creator,"APPROVED");
        assertThat(Stove.gateway.post(path(project.id())+"/release-requests",null,creator.headers()).status()).isEqualTo(202);
        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(data(project.id(),creator).path("currentReleaseRequest").path("status").asText()).isEqualTo("COMPLETED"));
        String code=data(project.id(),creator).path("productCode").asText();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(Stove.gateway.get("/api/v1/storefront/products/"+code).status()).isEqualTo(200));
        ObjectNode s=JSON.createObjectNode().put("buyerEmail",buyer.email()).put("otherEmail",other.email());
        s.set("product",Stove.gateway.get("/api/v1/storefront/products/"+code).data());
        JsonNode first=create(s,"approved",buyer); String no=first.path("orderNo").asText();
        s.set("pendingPayment",state("payments",no,buyer,"PENDING")); save(s);
        assertThat(get("orders",no,buyer).path("status").asText()).isEqualTo("PENDING");
        assertThat(Stove.gateway.get("/api/v1/payments/"+no,other.headers()).status()).isEqualTo(404);
        assertThat(Stove.gateway.get("/api/v1/payments").status()).isEqualTo(401);
        assertThat(Stove.gateway.get("/api/v1/payments",reviewer.headers()).status()).isEqualTo(403);
        assertThat(Stove.gateway.post("/api/v1/payments/callback",null).status()).isEqualTo(403);
        assertThat(Stove.gateway.post("/api/v1/payments/simulator/approve",null,buyer.headers()).status()).isEqualTo(403);
        E2eClient.Response list=Stove.gateway.get("/api/v1/payments?buyerSubject="+other.subject(),buyer.headers());
        assertThat(list.data().path("totalElements").asInt()).isEqualTo(1);
        assertThat(list.data().toString()).doesNotContain("buyerSubject","requestEventId","resultEventId","providerTransactionId");
    }
    @Test void approvesDurableReceiptAfterRestart() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=buyer(s); finalState(s,"approved",buyer,"PAID");
        assertThat(s.path("approvedPayment").path("paymentId")).isEqualTo(s.path("pendingPayment").path("paymentId"));
    }
    @Test void explicitDeclineUpdatesOrderAndPreservesEarlierApproval() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=newBuyer(s,"declined"); create(s,"declined",buyer); finalState(s,"declined",buyer,"FAILED");
        assertThat(s.path("declinedPayment").path("failureReasonCode").asText()).isEqualTo("DECLINED");
        assertThat(get("payments",number(s,"approved"),buyer(s))).isEqualTo(s.path("approvedPayment"));
    }
    @Test void losesResponseAfterSupplierCommit() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=buyer(s,"declined"); s.put("lostBuyerEmail",s.path("declinedBuyerEmail").asText()); create(s,"lost",buyer);
        s.set("lostPending",state("payments",number(s,"lost"),buyer,"PENDING")); save(s);
        assertThat(get("orders",number(s,"lost"),buyer).path("status").asText()).isEqualTo("PENDING");
    }
    @Test void recoversLostResponseAfterProcessRestart() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=buyer(s,"lost"); finalState(s,"lost",buyer,"PAID");
        assertThat(s.path("lostPayment").path("paymentId")).isEqualTo(s.path("lostPending").path("paymentId"));
    }
    @Test void rollsBackResultAfterOutboxInsertion() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=newBuyer(s,"rollback"); create(s,"rollback",buyer);
        s.set("rollbackPending",state("payments",number(s,"rollback"),buyer,"PENDING")); save(s);
        assertThat(get("orders",number(s,"rollback"),buyer).path("status").asText()).isEqualTo("PENDING");
    }
    @Test void recoversResultTransactionAfterProcessRestart() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=buyer(s,"rollback"); finalState(s,"rollback",buyer,"PAID");
        assertThat(s.path("rollbackPayment").path("paymentId")).isEqualTo(s.path("rollbackPending").path("paymentId"));
    }
    @Test void preparesReceiptBeforeOrderAndCatalogStop() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=newBuyer(s,"offline"); create(s,"offline",buyer);
        state("payments",number(s,"offline"),buyer,"PENDING");
    }
    @Test void paysWithoutOrderOrCatalogAndReadsWhileBrokerDown() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=buyer(s,"offline");
        JsonNode payment=state("payments",number(s,"offline"),buyer,"PAID");
        s.set("offlinePayment",payment); save(s);
        assertThat(Stove.gateway.get("/api/v1/payments",buyer.headers()).data().path("totalElements").asInt()).isEqualTo(1);
        assertThat(get("payments",number(s,"approved"),buyer(s))).isEqualTo(s.path("approvedPayment"));
    }
    @Test void catchesUpOriginalResultAndReplaysOriginalEvents() throws Exception {
        ObjectNode s=saved(); ReviewJourney.Account buyer=buyer(s,"offline"); finalState(s,"offline",buyer,"PAID");
        assertThat(get("payments",number(s,"approved"),buyer(s))).isEqualTo(s.path("approvedPayment"));
        HashMap<String, Object> config=new HashMap<String,Object>(); config.put("bootstrap.servers",System.getProperty("stove.e2e.kafka-brokers"));
        HashMap<String, Object> consumerConfig=new HashMap<>(config); consumerConfig.put("group.id","payment-evidence-"+UUID.randomUUID());
        consumerConfig.put("auto.offset.reset","earliest"); consumerConfig.put("enable.auto.commit",false);
        consumerConfig.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer"); consumerConfig.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        HashMap<String, Object> producerConfig=new HashMap<>(config); producerConfig.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer"); producerConfig.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");
        HashMap<String, DomainEvent> originals=new HashMap<String,DomainEvent>();
        try(KafkaConsumer<String, String> consumer=new KafkaConsumer<String,String>(consumerConfig)) {
            consumer.subscribe(List.of(Topics.ORDER,Topics.PAYMENT)); long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
            while(originals.size()<2 && System.nanoTime()<deadline) for(ConsumerRecord<String, String> record:consumer.poll(Duration.ofMillis(500))) {
                JsonNode body=JSON.readTree(record.value()); if(!number(s,"offline").equals(body.path("orderNo").asText())) continue;
                String type=new String(record.headers().lastHeader(EventHeaders.EVENT_TYPE).value(),StandardCharsets.UTF_8);
                if(EventType.ORDER_PLACED.equals(type)) originals.put(type,JSON.treeToValue(body,OrderPlacedEvent.class));
                if(EventType.ORDER_PAYMENT_COMPLETED.equals(type)) originals.put(type,JSON.treeToValue(body,OrderPaymentCompletedEvent.class));
            }
        }
        assertThat(originals).hasSize(2);
        OrderPlacedEvent placed=(OrderPlacedEvent)originals.get(EventType.ORDER_PLACED); OrderPaymentCompletedEvent result=(OrderPaymentCompletedEvent)originals.get(EventType.ORDER_PAYMENT_COMPLETED);
        assertThat(result.requestEventId()).isEqualTo(placed.eventId()); assertThat(result.amount()).isEqualTo(placed.amount());
        assertThat(result.paymentId()).isEqualTo(s.path("offlinePayment").path("paymentId").asText());
        try(KafkaProducer<String, String> producer=new KafkaProducer<String,String>(producerConfig)) {
            for(DomainEvent event:originals.values()) for(int i=0;i<2;i++) {
                ProducerRecord<String, String> record=new ProducerRecord<String,String>(event.topic(),event.partitionKey(),JSON.writeValueAsString(event));
                record.headers().add(EventHeaders.EVENT_ID,event.eventId().getBytes(StandardCharsets.UTF_8)); record.headers().add(EventHeaders.EVENT_TYPE,event.eventType().getBytes(StandardCharsets.UTF_8));
                producer.send(record).get(15,TimeUnit.SECONDS);
            }
        }
        s.put("replayedRequestEventId",placed.eventId()).put("replayedResultEventId",result.eventId()); save(s);
    }
    @Test void preservesAllResultsAfterReplayAndRestart() throws Exception {
        ObjectNode s=saved();
        for(String name:List.of("approved","declined","lost","rollback","offline")) {
            Account buyer=buyer(s,name);
            assertThat(get("payments",number(s,name),buyer)).isEqualTo(s.path(name+"Payment"));
            assertThat(get("orders",number(s,name),buyer)).isEqualTo(s.path(name+"Order"));
        }
    }
}
