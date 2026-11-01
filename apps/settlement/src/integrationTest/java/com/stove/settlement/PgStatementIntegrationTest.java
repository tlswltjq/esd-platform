package com.stove.settlement;

import static com.stove.settlement.PgStatementFixtures.page;
import static com.stove.settlement.PgStatementFixtures.query;
import static com.stove.settlement.PgStatementFixtures.row;
import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.PgFunding;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementTotals;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.port.CatalogOwners;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.service.ReceivePgStatement;
import com.stove.settlement.core.service.ReconcilePgStatement;
import com.stove.settlement.core.service.RecordPaidSale;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "stove.settlement.tosspay.enabled=true","stove.settlement.tosspay.scheduled-enabled=false",
        "stove.settlement.tosspay.api-key=sk_live_contract-fixture-only","stove.settlement.tosspay.environment=LIVE",
        "stove.settlement.tosspay.merchant-id=merchant-fixture","stove.settlement.tosspay.portone-store-id=store-fixture",
        "stove.settlement.tosspay.portone-channel-key=channel-fixture","stove.settlement.tosspay.identity-mapping-verified=false",
        "stove.outbox.relay-enabled=false","stove.messaging.ops.enabled=false","spring.kafka.listener.auto-startup=false"})
class PgStatementIntegrationTest extends SettlementIntegrationTestBase {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,String> RESPONSES=new ConcurrentHashMap<>();
    private static final AtomicInteger CALLS=new AtomicInteger();
    private static final AtomicInteger ERRORS=new AtomicInteger();
    private static final AtomicInteger LATE_STATUS=new AtomicInteger(200);
    private static final HttpServer SUPPLIER;
    static {
        try {
            SUPPLIER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            SUPPLIER.createContext("/",exchange -> {
                CALLS.incrementAndGet();
                if(!"POST".equals(exchange.getRequestMethod()) || !"/api/v2/settlement-details".equals(exchange.getRequestURI().getPath())) {
                    ERRORS.incrementAndGet();exchange.sendResponseHeaders(405,-1);exchange.close();return;
                }
                JsonNode body=JSON.readTree(exchange.getRequestBody());
                if(!"sk_live_contract-fixture-only".equals(body.path("apiKey").asText())) ERRORS.incrementAndGet();
                String cursor=body.path("nextCursor").asText("");String response=RESPONSES.getOrDefault(cursor,"{}");
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(cursor.isEmpty()?200:LATE_STATUS.get(),bytes.length);
                try(OutputStream out=exchange.getResponseBody()) {out.write(bytes);}
            });SUPPLIER.start();Runtime.getRuntime().addShutdownHook(new Thread(() -> SUPPLIER.stop(0)));
        } catch(Exception failure) {throw new ExceptionInInitializerError(failure);}
    }
    @DynamicPropertySource static void source(DynamicPropertyRegistry properties) {
        properties.add("stove.settlement.tosspay.base-url",() -> "http://127.0.0.1:"+SUPPLIER.getAddress().getPort());
    }
    @Autowired ReceivePgStatement receive;
    @Autowired ReconcilePgStatement reconcile;
    @Autowired RecordPaidSale record;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PgPayments payments;
    @MockitoBean CatalogOwners owners;
    @MockitoBean Clock clock;
    @LocalServerPort int port;
    @BeforeEach void clean() {
        for(String table:List.of("settlement_pg_statement_item","settlement_pg_statement_page","settlement_pg_statement","settlement_pg_statement_entry",
                "settlement_refund_cancellation","settlement_refund","pg_receipt_item","pg_receipt","settlement_sale","seller_monthly_settlement",
                "settlement_pg_fee","settlement_pg_transaction","processed_event")) jdbc.update("DELETE FROM "+table);
        RESPONSES.clear();CALLS.set(0);ERRORS.set(0);LATE_STATUS.set(200);when(clock.instant()).thenReturn(NOW);
        when(owners.require(anyString())).thenReturn(new ProductOwner(1L,"game-1","usr_seller"));
    }
    @AfterEach void contract() {assertThat(ERRORS.get()).isZero();}
    int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    void response(List<PgStatementRow> rows) {RESPONSES.put("",page(rows,"",PgStatementTotals.sum(rows)).toString());}
    PgStatementRow pay(String id,String payment) {return row("PAY",id,"token-"+payment,payment,10000,-300,-30);}
    record Reply(int status,JsonNode body) { }
    Reply http(String method,String path,String role,Object body) {
        RestClient.RequestBodySpec request=RestClient.create("http://127.0.0.1:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(role!=null) request.header("Authorization","Bearer "+token(role.equals("ADMIN")?"usr_admin":"usr_seller",role));
        if(body!=null) request.body(body);
        return request.exchange((sent,received) -> new Reply(received.getStatusCode().value(),received.bodyTo(JsonNode.class)));
    }
    @Test void collectsCompleteHttpEvidenceAndExposesOnlyAdministratorReportsAndRawPages() {
        PgStatementRow pay=pay("pay-1","payment-1");PgStatementRow refund=row("REFUND","refund-1",pay.payToken(),pay.orderNo(),-10000,300,30);
        PgStatementTotals totals=PgStatementTotals.sum(List.of(pay,refund));
        RESPONSES.put("",page(List.of(pay),"next",totals).toString());RESPONSES.put("next",page(List.of(refund),"",totals).toString());
        Map<String,String> input=Map.of("dateType","DUE","baseDate","2026-10-01");
        assertThat(http("POST","/api/v1/settlements/pg-statements",null,input).status()).isEqualTo(401);
        assertThat(http("POST","/api/v1/settlements/pg-statements","CREATOR",input).status()).isEqualTo(403);assertThat(CALLS.get()).isZero();
        Reply created=http("POST","/api/v1/settlements/pg-statements","ADMIN",input);assertThat(created.status()).isEqualTo(200);
        long id=created.body().path("data").path("id").asLong();assertThat(id).isPositive();
        assertThat(created.body().path("data").path("reportedNetAmount").asLong()).isZero();
        assertThat(created.body().path("data").path("status").asText()).isEqualTo("PENDING");
        assertThat(count("settlement_pg_statement")).isEqualTo(1);assertThat(count("settlement_pg_statement_page")).isEqualTo(2);
        assertThat(count("settlement_pg_statement_entry")).isEqualTo(2);assertThat(count("settlement_pg_statement_item")).isEqualTo(2);
        String path="/api/v1/settlements/pg-statements/"+id;
        assertThat(http("GET",path+"/items?size=1","ADMIN",null).body().path("data").path("totalElements").asLong()).isEqualTo(2);
        assertThat(http("GET",path+"/pages/1","ADMIN",null).body().path("data").path("responseBody").asText()).isEqualTo(RESPONSES.get("next"));
        assertThat(http("GET",path,"ADMIN",null).body().toString()).doesNotContain("sk_live_","responseBody");
        assertThat(http("GET",path+"/items","CREATOR",null).status()).isEqualTo(403);
        assertThat(http("GET",path+"/pages/0",null,null).status()).isEqualTo(401);
        int calls=CALLS.get();assertThat(http("POST",path+"/reconcile","ADMIN",null).status()).isEqualTo(200);assertThat(CALLS.get()).isEqualTo(calls);
        assertThat(count("pg_receipt")).isZero();assertThat(count("settlement_pg_fee")).isZero();
    }
    @Test void duplicateAndDifferentDateQueriesShareOneImmutableTransactionWithoutDoubleCounting() {
        response(List.of(pay("pay-1","payment-1")));PgStatement first=receive.execute(query(),"admin-one");
        PgStatement duplicate=receive.execute(query(),"admin-two");assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(duplicate.getCollectedBy()).isEqualTo("admin-one");assertThat(duplicate.getReceivedAt()).isEqualTo(first.getReceivedAt());
        receive.execute(new PgStatementQuery("SETTLE",query().baseDate()),"admin-two");
        assertThat(count("settlement_pg_statement")).isEqualTo(2);assertThat(count("settlement_pg_statement_entry")).isEqualTo(1);
        assertThat(count("settlement_pg_statement_item")).isEqualTo(2);
    }
    @Test void concurrentSameReportConvergesOnOneSourceObservation() throws Exception {
        response(List.of(pay("pay-1","payment-1")));CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(4)) {
            List<Future<PgStatement>> calls=new ArrayList<>();
            for(int index=0;index<4;index++) calls.add(pool.submit(() -> {start.await();return receive.execute(query(),"admin");}));
            start.countDown();List<Long> ids=new ArrayList<>();for(Future<PgStatement> call:calls) ids.add(call.get(30,TimeUnit.SECONDS).getId());
            assertThat(ids.stream().distinct().count()).isEqualTo(1);
        }
        assertThat(count("settlement_pg_statement")).isEqualTo(1);assertThat(count("settlement_pg_statement_entry")).isEqualTo(1);
    }
    @Test void laterSourceCorrectionCannotOverwriteFirstTransactionOrItsRawPages() {
        PgStatementRow first=pay("pay-1","payment-1");response(List.of(first));receive.execute(query(),"admin");
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM settlement_pg_statement_entry");
        response(List.of(row("PAY",first.transactionId(),first.payToken(),first.orderNo(),10000,-301,-30)));
        assertThatThrownBy(() -> receive.execute(query(),"admin")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_statement_entry")).isEqualTo(before);
        assertThat(count("settlement_pg_statement")).isEqualTo(1);assertThat(count("settlement_pg_statement_page")).isEqualTo(1);
    }
    @Test void incompleteHttpAndActualDatabaseWriteFailureCannotLeaveHalfAStatement() {
        PgStatementRow pay=pay("pay-1","payment-1");PgStatementTotals totals=PgStatementTotals.sum(List.of(pay));
        RESPONSES.put("",page(List.of(pay),"next",totals).toString());RESPONSES.put("next",page(List.of(),"",totals).toString());LATE_STATUS.set(503);
        assertThatThrownBy(() -> receive.execute(query(),"admin")).isInstanceOf(BusinessException.class);noStatement();
        LATE_STATUS.set(200);response(List.of(pay));
        jdbc.execute("ALTER TABLE settlement_pg_statement_item ADD CONSTRAINT ck_fixture_statement_failure CHECK(item_position<0)");
        try {assertThatThrownBy(() -> receive.execute(query(),"admin")).isInstanceOf(RuntimeException.class);noStatement();}
        finally {jdbc.execute("ALTER TABLE settlement_pg_statement_item DROP CHECK ck_fixture_statement_failure");}
        receive.execute(query(),"admin");assertThat(count("settlement_pg_statement")).isEqualTo(1);
    }
    @Test void lateTestPurchaseStaysUnconfirmedAndItsSellerMonthNeverChanges() {
        PaidOrder order=order();PgStatementRow row=pay("pay-1",order.paymentId());response(List.of(row));
        PgStatement statement=receive.execute(query(),"admin");assertThat(jdbc.queryForObject("SELECT check_code FROM settlement_pg_statement_item",String.class)).isEqualTo("MISSING_SALE");
        PgPayment payment=new PgPayment(order.paymentId(),order.orderNo(),order.amount(),order.currency(),new PgEvidence("PORTONE_TEST",row.payToken(),order.occurredAt()),
                null,new PgFunding(order.amount(),order.amount(),0,"store-fixture","channel-fixture"));
        when(payments.require(order.paymentId())).thenReturn(payment);record.execute(order);
        Map<String,Object> month=jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
        reconcile.execute(statement.getId());assertThat(jdbc.queryForObject("SELECT check_code FROM settlement_pg_statement_item",String.class)).isEqualTo("SOURCE_ENVIRONMENT_PENDING");
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(month);assertThat(count("settlement_pg_fee")).isZero();
        assertThat(http("GET","/api/v1/settlements/months","ADMIN",null).body().path("data").path("content").get(0).path("pgFeeAmount").isNull()).isTrue();
    }
    @Test void validationAndTransactionBoundariesPreventInvalidOrUnreadyQueries() {
        assertThat(http("POST","/api/v1/settlements/pg-statements","ADMIN",Map.of("dateType","DUE","baseDate","2026-13-01")).status()).isEqualTo(400);
        assertThat(http("POST","/api/v1/settlements/pg-statements","ADMIN",Map.of("dateType","OTHER","baseDate","2026-10-01")).status()).isEqualTo(400);
        assertThatThrownBy(() -> receive.execute(new PgStatementQuery("SETTLE",LocalDate.of(2026,10,18)),"admin")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> receive.execute(query(),"admin"))).isInstanceOf(IllegalStateException.class);
        assertThat(CALLS.get()).isZero();noStatement();
        assertThat(http("GET","/api/v1/settlements/pg-statements?size=0","ADMIN",null).status()).isEqualTo(400);
        assertThat(http("GET","/api/v1/settlements/pg-statements/999999","ADMIN",null).status()).isEqualTo(404);
    }
    void noStatement() {
        for(String table:List.of("settlement_pg_statement","settlement_pg_statement_page","settlement_pg_statement_entry","settlement_pg_statement_item"))
            assertThat(count(table)).as(table).isZero();
    }
}
