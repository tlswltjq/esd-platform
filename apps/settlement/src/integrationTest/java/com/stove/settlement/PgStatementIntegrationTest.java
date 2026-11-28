package com.stove.settlement;

import static com.stove.settlement.PgStatementFixtures.page;
import static com.stove.settlement.PgStatementFixtures.query;
import static com.stove.settlement.PgStatementFixtures.row;
import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.PgFunding;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementPage;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementTotals;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.port.CatalogOwners;
import com.stove.settlement.core.port.PgPayments;
import com.stove.settlement.core.port.PgStatementSource;
import com.stove.settlement.core.service.ReceivePgStatement;
import com.stove.settlement.core.service.ReconcilePendingPgStatements;
import com.stove.settlement.core.service.ReconcilePgStatement;
import com.stove.settlement.core.service.RecordPaidSale;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** PG 어댑터가 없는 동안(결정 remove-pg-adapters) 정산 자료 출처 포트를 대역으로 채워 수집·대사 코어를 검증한다. */
class PgStatementIntegrationTest extends SettlementIntegrationTestBase {
    private static final PgStatementScope SCOPE=new PgStatementScope("merchant-fixture","LIVE","store-fixture","channel-fixture",true);
    @Autowired ReceivePgStatement receive;
    @Autowired ReconcilePgStatement reconcile;
    @Autowired ReconcilePendingPgStatements recovery;
    @Autowired RecordPaidSale record;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PgStatementSource source;
    private final AtomicInteger fetches=new AtomicInteger();
    @MockitoBean PgPayments payments;
    @MockitoBean CatalogOwners owners;
    @MockitoBean Clock clock;
    @LocalServerPort int port;
    @BeforeEach void clean() {
        for(String table:List.of("settlement_pg_cost_posting","settlement_pg_statement_item","settlement_pg_statement_page","settlement_pg_statement","settlement_pg_statement_entry",
                "settlement_refund_cancellation","settlement_refund","pg_receipt_item","pg_receipt","settlement_sale","settlement_seller_carryover","seller_monthly_settlement",
                "settlement_pg_fee","settlement_pg_transaction","processed_event")) jdbc.update("DELETE FROM "+table);
        when(clock.instant()).thenReturn(NOW);
        when(owners.require(anyString())).thenReturn(new ProductOwner(1L,"game-1","usr_seller"));
    }
    int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    void response(List<PgStatementRow> rows) {
        PgStatementTotals totals=PgStatementTotals.sum(rows);
        respond(List.of(new PgStatementPage(null,"",page(rows,"",totals).toString())),rows,totals);
    }
    void respond(List<PgStatementPage> pages,List<PgStatementRow> rows,PgStatementTotals totals) {
        doAnswer(call -> {fetches.incrementAndGet();return new PgStatementReport(SCOPE,call.getArgument(0),pages,rows,totals);})
                .when(source).fetch(any(PgStatementQuery.class));
    }
    int fetchCount() {return fetches.get();}
    PgStatementRow pay(String id,String payment) {return row("PAY",id,"token-"+payment,payment,10000,-300,-30);}
    record Reply(int status,JsonNode body) { }
    Reply http(String method,String path,String role,Object body) {
        RestClient.RequestBodySpec request=RestClient.create("http://127.0.0.1:"+port).method(HttpMethod.valueOf(method)).uri(path);
        if(role!=null) request.header("Authorization","Bearer "+token(role.equals("ADMIN")?"usr_admin":"usr_seller",role));
        if(body!=null) request.body(body);
        return request.exchange((sent,received) -> new Reply(received.getStatusCode().value(),received.bodyTo(JsonNode.class)));
    }
    @Test void collectsCompleteEvidenceAndExposesOnlyAdministratorReportsAndRawPages() {
        PgStatementRow pay=pay("pay-1","payment-1");PgStatementRow refund=row("REFUND","refund-1",pay.payToken(),pay.orderNo(),-10000,300,30);
        PgStatementTotals totals=PgStatementTotals.sum(List.of(pay,refund));
        String lastPage=page(List.of(refund),"",totals).toString();
        respond(List.of(new PgStatementPage(null,"next",page(List.of(pay),"next",totals).toString()),new PgStatementPage("next","",lastPage)),List.of(pay,refund),totals);
        Map<String,String> input=Map.of("dateType","DUE","baseDate","2026-10-01");
        assertThat(http("POST","/api/v1/settlements/pg-statements",null,input).status()).isEqualTo(401);
        assertThat(http("POST","/api/v1/settlements/pg-statements","CREATOR",input).status()).isEqualTo(403);assertThat(fetchCount()).isZero();
        Reply created=http("POST","/api/v1/settlements/pg-statements","ADMIN",input);assertThat(created.status()).isEqualTo(200);
        long id=created.body().path("data").path("id").asLong();assertThat(id).isPositive();
        assertThat(created.body().path("data").path("reportedNetAmount").asLong()).isZero();
        assertThat(created.body().path("data").path("status").asText()).isEqualTo("PENDING");
        assertThat(count("settlement_pg_statement")).isEqualTo(1);assertThat(count("settlement_pg_statement_page")).isEqualTo(2);
        assertThat(count("settlement_pg_statement_entry")).isEqualTo(2);assertThat(count("settlement_pg_statement_item")).isEqualTo(2);
        String path="/api/v1/settlements/pg-statements/"+id;
        assertThat(http("GET",path+"/items?size=1","ADMIN",null).body().path("data").path("totalElements").asLong()).isEqualTo(2);
        assertThat(http("GET",path+"/pages/1","ADMIN",null).body().path("data").path("responseBody").asText()).isEqualTo(lastPage);
        assertThat(http("GET",path,"ADMIN",null).body().toString()).doesNotContain("sk_live_","responseBody");
        assertThat(http("GET",path+"/items","CREATOR",null).status()).isEqualTo(403);
        assertThat(http("GET",path+"/pages/0",null,null).status()).isEqualTo(401);
        int calls=fetchCount();assertThat(http("POST",path+"/reconcile","ADMIN",null).status()).isEqualTo(200);assertThat(fetchCount()).isEqualTo(calls);
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
    @Test void unavailableSourceAndActualDatabaseWriteFailureCannotLeaveHalfAStatement() {
        PgStatementRow pay=pay("pay-1","payment-1");
        doAnswer(call -> {fetches.incrementAndGet();throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);})
                .when(source).fetch(any(PgStatementQuery.class));
        assertThatThrownBy(() -> receive.execute(query(),"admin")).isInstanceOf(BusinessException.class);noStatement();
        response(List.of(pay));
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
        int calls=fetchCount();when(clock.instant()).thenReturn(Instant.parse("2026-12-20T03:00:00Z"));
        recovery.run();assertThat(fetchCount()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT check_code FROM settlement_pg_statement_item",String.class)).isEqualTo("SOURCE_ENVIRONMENT_PENDING");
        assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(month);assertThat(count("settlement_pg_fee")).isZero();
        assertThat(http("GET","/api/v1/settlements/months","ADMIN",null).body().path("data").path("content").get(0).path("pgFeeAmount").isNull()).isTrue();
    }
    @Test void validationAndTransactionBoundariesPreventInvalidOrUnreadyQueries() {
        assertThat(http("POST","/api/v1/settlements/pg-statements","ADMIN",Map.of("dateType","DUE","baseDate","2026-13-01")).status()).isEqualTo(400);
        assertThat(http("POST","/api/v1/settlements/pg-statements","ADMIN",Map.of("dateType","OTHER","baseDate","2026-10-01")).status()).isEqualTo(400);
        assertThatThrownBy(() -> receive.execute(new PgStatementQuery("SETTLE",LocalDate.of(2026,10,18)),"admin")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> receive.execute(query(),"admin"))).isInstanceOf(IllegalStateException.class);
        assertThat(fetchCount()).isZero();noStatement();
        assertThat(http("GET","/api/v1/settlements/pg-statements?size=0","ADMIN",null).status()).isEqualTo(400);
        assertThat(http("GET","/api/v1/settlements/pg-statements/999999","ADMIN",null).status()).isEqualTo(404);
    }
    String payoutPath() {return "/api/v1/settlements/pg-payouts?merchantId=merchant-fixture&environment=LIVE&month=2026-10";}
    @Test void productionFundingConstraintRejectsLivePromotionAndKeepsOriginalTestEvidence() {
        PaidOrder source=recordTestPurchase();
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM settlement_pg_transaction");
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_pg_transaction SET provider_channel_type='LIVE' WHERE payment_id=?",source.paymentId()))
                .isInstanceOfSatisfying(UncategorizedSQLException.class,failure -> {
                    assertThat(failure.getSQLException().getErrorCode()).isEqualTo(3819);
                    assertThat(failure.getSQLException().getMessage()).contains("ck_pg_transaction_funding");
                });
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_transaction")).isEqualTo(before);
        assertThatThrownBy(() -> new PgEvidence("PORTONE_LIVE","token",NOW)).isInstanceOf(BusinessException.class);
        assertThat(count("settlement_pg_cost_posting")).isZero();
    }
    @Test void reconciliationWriteFailureRollsBackActualDatabaseChangesAndStoredSourceCanRetry() {
        PaidOrder source=order();response(List.of(pay("pay-rollback",source.paymentId())));
        PgStatement report=receive.execute(query(),"admin");
        recordTestPurchase(source);Map<String,Object> before=jdbc.queryForMap("SELECT * FROM settlement_pg_statement_item");
        jdbc.execute("ALTER TABLE settlement_pg_statement_item ADD CONSTRAINT ck_fixture_reconcile_failure CHECK(check_code<>'SOURCE_ENVIRONMENT_PENDING')");
        try {assertThatThrownBy(() -> reconcile.execute(report.getId())).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("ALTER TABLE settlement_pg_statement_item DROP CHECK ck_fixture_reconcile_failure");}
        assertThat(jdbc.queryForMap("SELECT * FROM settlement_pg_statement_item")).isEqualTo(before);
        assertThat(count("settlement_pg_statement_entry")).isEqualTo(1);assertThat(count("settlement_pg_cost_posting")).isZero();
        int calls=fetchCount();recovery.run();assertThat(fetchCount()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("SELECT check_code FROM settlement_pg_statement_item",String.class)).isEqualTo("SOURCE_ENVIRONMENT_PENDING");
    }
    PaidOrder recordTestPurchase() {return recordTestPurchase(order());}
    PaidOrder recordTestPurchase(PaidOrder source) {
        PgPayment payment=new PgPayment(source.paymentId(),source.orderNo(),source.amount(),source.currency(),
                new PgEvidence("PORTONE_TEST","token-"+source.paymentId(),source.occurredAt()),null,
                new PgFunding(source.amount(),source.amount(),0,"store-fixture","channel-fixture"));
        when(payments.require(source.paymentId())).thenReturn(payment);record.execute(source);return source;
    }
    @Test void unlinkedPayoutsRemainVisibleAndScopesAndMissingEvidenceAreExplicit() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-15T03:00:00Z"));
        PaidOrder absent=order();response(List.of(pay("unlinked",absent.paymentId())));receive.execute(query(),"admin");
        JsonNode data=http("GET",payoutPath(),"ADMIN",null).body().path("data");
        assertThat(data.path("recordCount").asLong()).isEqualTo(1);assertThat(data.path("linkedCount").asLong()).isZero();
        assertThat(data.path("status").asText()).isEqualTo("PENDING_OR_MISMATCH");
        assertThat(http("GET",payoutPath().replace("merchant-fixture","other"),"ADMIN",null).body().path("data").path("status").asText()).isEqualTo("NO_EVIDENCE");
        assertThat(http("GET",payoutPath().replace("2026-10","bad"),"ADMIN",null).status()).isEqualTo(400);
        String items=payoutPath().replace("pg-payouts?","pg-payouts/items?");
        Reply detail=http("GET",items,"ADMIN",null);assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().path("data").path("content").get(0).path("checkCode").asText()).isEqualTo("MISSING_SALE");
        assertThat(detail.body().path("data").path("content").get(0).path("businessMonth").isNull()).isTrue();
        assertThat(http("GET",payoutPath(),"CREATOR",null).status()).isEqualTo(403);
        assertThat(http("GET",payoutPath(),"MEMBER",null).status()).isEqualTo(403);
        assertThat(http("GET",payoutPath(),null,null).status()).isEqualTo(401);
        assertThat(count("settlement_pg_cost_posting")).isZero();
    }
    void noStatement() {
        for(String table:List.of("settlement_pg_statement","settlement_pg_statement_page","settlement_pg_statement_entry","settlement_pg_statement_item"))
            assertThat(count(table)).as(table).isZero();
    }
}
