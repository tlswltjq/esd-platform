package com.stove.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.mockpg.core.domain.ChargeRequest;
import com.stove.mockpg.core.domain.Charge;
import com.stove.mockpg.core.domain.ProviderException;
import com.stove.mockpg.core.service.ProcessProviderLedger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "mock-pg.payment-key=p","mock-pg.settlement-key=s","mock-pg.control-key=c"})
class ProviderIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl); registry.add("spring.datasource.username",MYSQL::getUsername);
        registry.add("spring.datasource.password",MYSQL::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    record Reply(int status,JsonNode body) { }
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM pg_settlement_item"); jdbc.update("DELETE FROM pg_settlement"); jdbc.update("DELETE FROM pg_charge");
    }
    Reply http(String method,String path,String key,Object body,String reference) {
        RestClient.RequestBodySpec request=RestClient.create("http://127.0.0.1:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(key!=null) request.header("X-Pg-Api-Key",key);
        if(reference!=null) request.header("Idempotency-Key",reference);
        if(body!=null) request.body(body);
        return request.exchange((r,s) -> new Reply(s.getStatusCode().value(),s.bodyTo(JsonNode.class)));
    }
    ChargeRequest input(long amount) { return new ChargeRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),amount,"KRW"); }
    Reply charge(ChargeRequest input) { return http("POST","/v1/charges","p",input,input.paymentId()); }
    Reply run(String id) { return http("POST","/test-support/v1/settlement-runs","c",Map.of("requestId",id),null); }

    @Test void clientScopesAndStrictJsonCannotCreateUnauthorizedDecisions() {
        ChargeRequest input=input(10000);
        assertThat(http("POST","/v1/charges",null,input,input.paymentId()).status()).isEqualTo(401);
        assertThat(http("POST","/v1/charges","s",input,input.paymentId()).status()).isEqualTo(403);
        assertThat(http("POST","/test-support/v1/settlement-runs","p",Map.of("requestId",UUID.randomUUID().toString()),null).status()).isEqualTo(403);
        assertThat(http("POST","/v1/charges","p",Map.of("paymentId",input.paymentId(),"orderNo",input.orderNo(),"amount",1.5,"currency","KRW"),input.paymentId()).status()).isEqualTo(400);
        assertThat(http("POST","/v1/charges","p",input,"different").status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_charge",Long.class)).isZero();
    }
    @Test void samePaymentReferenceIsStableAndCannotBeReusedForAnotherAmount() {
        ChargeRequest input=input(10000); Reply first=charge(input);
        assertThat(first.status()).isEqualTo(200); assertThat(first.body().path("pgFeeAmount").asLong()).isEqualTo(300);
        assertThat(charge(input).body()).isEqualTo(first.body());
        assertThat(http("GET","/v1/charges/by-reference/"+input.paymentId(),"s",null,null).body()).isEqualTo(first.body());
        assertThat(charge(new ChargeRequest(input.paymentId(),input.orderNo(),9999,"KRW")).status()).isEqualTo(409);
    }
    @Test void policyVersionCannotBeReusedWithAnotherRateAndExistingDecisionsStayStable() {
        ChargeRequest first=input(10000);charge(first);
        ProcessProviderLedger changed=new ProcessProviderLedger(jdbc,transactions,Clock.systemUTC(),Charge.Decision.APPROVED,"SIMULATED_FEE_V1",500);
        assertThat(changed.charge(first).pgFeeAmount()).isEqualTo(300);
        assertThatThrownBy(() -> changed.charge(input(10000))).isInstanceOf(ProviderException.class).hasMessage("FEE_POLICY_CONFLICT");
        ProcessProviderLedger next=new ProcessProviderLedger(jdbc,transactions,Clock.systemUTC(),Charge.Decision.APPROVED,"SIMULATED_FEE_V2",500);
        assertThat(next.charge(input(10000)).pgFeeAmount()).isEqualTo(500);
    }
    @Test void transactionRoundingAndPayoutRetryPreserveImmutableItems() {
        for(int i=0;i<3;i++) assertThat(charge(input(17)).status()).isEqualTo(200);
        String request=UUID.randomUUID().toString(); Reply first=run(request);
        assertThat(first.status()).isEqualTo(200); assertThat(first.body().path("pgFeeAmountTotal").asLong()).isEqualTo(3);
        assertThat(first.body().path("paymentAmountTotal").asLong()).isEqualTo(51);
        assertThat(first.body().path("payoutAmount").asLong()).isEqualTo(48);
        assertThat(run(request).body()).isEqualTo(first.body());
        assertThat(run(UUID.randomUUID().toString()).status()).isEqualTo(409);
        assertThat(http("GET","/v1/settlements/"+first.body().path("providerSettlementId").asText(),"s",null,null).body()).isEqualTo(first.body());
    }
    @Test void concurrentApprovalsAndRunsNeverAllocateTheSameTransactionTwice() throws Exception {
        ChargeRequest input=input(10000); CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(4)) {
            List<Future<Reply>> approvals=new ArrayList<Future<Reply>>();
            for(int i=0;i<4;i++) approvals.add(pool.submit(() -> { start.await(); return charge(input); }));
            start.countDown();
            String transaction=null;
            for(Future<Reply> task:approvals) {
                Reply reply=task.get(20,TimeUnit.SECONDS); assertThat(reply.status()).isEqualTo(200);
                String id=reply.body().path("providerTransactionId").asText();
                if(transaction==null) transaction=id; else assertThat(id).isEqualTo(transaction);
            }
            String request=UUID.randomUUID().toString();
            Future<Reply> a=pool.submit(() -> run(request)); Future<Reply> b=pool.submit(() -> run(request));
            Reply first=a.get(20,TimeUnit.SECONDS); Reply second=b.get(20,TimeUnit.SECONDS);
            assertThat(first.status()).isEqualTo(200); assertThat(second.body()).isEqualTo(first.body());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_settlement_item",Long.class)).isEqualTo(1);
        }
    }
    @Test void lateCommitAndUnknownLegacyFeesAreHandledWithoutOmittingEligibleCharges() {
        charge(input(10000)); run(UUID.randomUUID().toString());
        ChargeRequest late=input(10000); charge(late);
        jdbc.update("UPDATE pg_charge SET provider_occurred_at='2026-01-01' WHERE payment_id=?",late.paymentId());
        ChargeRequest unknown=input(10000); charge(unknown);
        jdbc.update("UPDATE pg_charge SET pg_fee_policy_id=NULL,pg_fee_rate_bps=NULL,pg_fee_amount=NULL WHERE payment_id=?",unknown.paymentId());
        Reply next=run(UUID.randomUUID().toString());
        assertThat(next.body().path("recordCount").asLong()).isEqualTo(1);
        assertThat(next.body().path("items").get(0).path("paymentId").asText()).isEqualTo(late.paymentId());
    }
    @Test void partialPayoutFailureRollsBackHeaderItemsAndRequestIdentity() {
        charge(input(10000)); String request=UUID.randomUUID().toString();
        jdbc.execute("CREATE TRIGGER fail_payout BEFORE INSERT ON pg_settlement_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'");
        try {
            assertThat(run(request).status()).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_settlement",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_settlement_item",Long.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_payout"); }
        assertThat(run(request).status()).isEqualTo(200);
    }
}
