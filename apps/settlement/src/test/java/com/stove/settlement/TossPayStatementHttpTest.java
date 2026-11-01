package com.stove.settlement;

import static com.stove.settlement.PgStatementFixtures.page;
import static com.stove.settlement.PgStatementFixtures.query;
import static com.stove.settlement.PgStatementFixtures.row;
import static com.stove.settlement.PgStatementFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.adapter.TossPayStatementHttpClient;
import com.stove.settlement.config.TossPayStatementProperties;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementTotals;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TossPayStatementHttpTest {
    private static final String KEY="sk_live_contract-fixture-only";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Map<String,String> responses=new ConcurrentHashMap<>();
    private final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    private final AtomicInteger status=new AtomicInteger(200);
    private HttpServer server;
    private RestClient http;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v2/settlement-details",exchange -> {
            if(!"POST".equals(exchange.getRequestMethod())) {exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            JsonNode request=JSON.readTree(exchange.getRequestBody());requests.add(request);
            String cursor=request.path("nextCursor").asText("");String response=responses.getOrDefault(cursor,"{}");
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(cursor.isEmpty()?200:status.get(),bytes.length);
            try(OutputStream out=exchange.getResponseBody()) {out.write(bytes);}
        });server.start();http=RestClient.create("http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop() {server.stop(0);}
    TossPayStatementHttpClient client() {return new TossPayStatementHttpClient(http,KEY,scope(),10);}
    PgStatementRow pay() {return row("PAY","pay-1","token","payment",9000,-300,-30);}
    @Test void completePagesKeepRawBodiesGlobalTotalsAndReadOnlyRequestContract() {
        PgStatementRow refund=row("REFUND","refund-1","token","payment",-9000,300,30);
        PgStatementTotals total=PgStatementTotals.sum(List.of(pay(),refund));
        responses.put("",page(List.of(pay()),"next",total).toString());responses.put("next",page(List.of(refund),"",total).toString());
        PgStatementReport report=client().fetch(query());assertThat(report.rows()).containsExactly(pay(),refund);
        assertThat(report.pages()).hasSize(2);assertThat(report.totals().netAmount()).isZero();
        assertThat(report.pages().getFirst().getResponseBody()).isEqualTo(responses.get(""));
        assertThat(requests.getFirst().path("apiKey").asText()).isEqualTo(KEY);assertThat(requests.getFirst().has("nextCursor")).isFalse();
        assertThat(requests.getFirst().path("baseDate").asText()).isEqualTo("20261001");assertThat(requests.get(1).path("nextCursor").asText()).isEqualTo("next");
    }
    @Test void incompletePagesCyclesDuplicateTransactionsAndChangingTotalsFailTheWholeRead() {
        PgStatementTotals totals=PgStatementTotals.sum(List.of(pay()));responses.put("",page(List.of(pay()),"next",totals).toString());
        responses.put("next",page(List.of(),"next",totals).toString());fails();
        responses.put("next",page(List.of(pay()),"",totals).toString());fails();
        responses.put("next",page(List.of(),"",new PgStatementTotals(0,0,0,0)).toString());fails();
        status.set(503);fails();
        assertThatThrownBy(() -> new TossPayStatementHttpClient(http,KEY,scope(),1).fetch(query())).isInstanceOf(BusinessException.class);
    }
    @Test void unknownFieldsArePreservedButCoercionBrokenRowsAndJsonAmbiguityAreRejected() {
        ObjectNode good=page(List.of(pay()),"",PgStatementTotals.sum(List.of(pay())));good.put("futureProviderField","kept");
        responses.put("",good.toString());assertThat(client().fetch(query()).pages().getFirst().getResponseBody()).contains("futureProviderField");
        List<String> invalid=new ArrayList<>();
        for(String field:List.of("amount","fee","vat","feeVatSum")) {
            ObjectNode bad=good.deepCopy();((ObjectNode)bad.path("transactionList").get(0)).put(field,"0");invalid.add(bad.toString());
        }
        ObjectNode date=good.deepCopy();((ObjectNode)date.path("transactionList").get(0)).put("dueDate","20260230");invalid.add(date.toString());
        ObjectNode unknown=good.deepCopy();((ObjectNode)unknown.path("transactionList").get(0)).put("transactionType","OTHER");invalid.add(unknown.toString());
        invalid.add(good+"{}");invalid.add(good.toString().replace("\"nextCursor\":\"\"","\"nextCursor\":\"\",\"nextCursor\":\"\""));
        invalid.add(good.deepCopy().put("echoedKey",KEY).toString());
        for(String body:invalid) {responses.put("",body);fails();}
    }
    @Test void emptyTestReportDoesNotInventZeroFeesForAnyPurchaseAndTestRowsAreRejected() {
        TossPayStatementHttpClient test=new TossPayStatementHttpClient(http,"sk_test_contract-fixture-only",new PgStatementScope("merchant","TEST","store","channel",false),10);
        responses.put("",page(List.of(),"",new PgStatementTotals(0,0,0,0)).toString());assertThat(test.fetch(query()).rows()).isEmpty();
        responses.put("",page(List.of(pay()),"",PgStatementTotals.sum(List.of(pay()))).toString());
        assertThatThrownBy(() -> test.fetch(query())).isInstanceOf(BusinessException.class);
    }
    @Test void configurationRejectsWrongCredentialDestinationsAndHidesSecrets() {
        for(String uri:List.of("https://other.example","http://pay.toss.im","https://pay.toss.im?key=x","https://user@pay.toss.im","https://pay.toss.im/wrong"))
            assertThatThrownBy(() -> properties(uri,KEY,"LIVE").requireConfigured()).isInstanceOf(IllegalArgumentException.class);
        properties("https://pay.toss.im",KEY,"LIVE").requireConfigured();
        assertThat(properties("https://pay.toss.im",KEY,"LIVE").toString()).doesNotContain(KEY);
        assertThatThrownBy(() -> properties("https://pay.toss.im",KEY,"TEST").requireConfigured()).isInstanceOf(IllegalArgumentException.class);
    }
    TossPayStatementProperties properties(String uri,String key,String environment) {
        return new TossPayStatementProperties(true,URI.create(uri),key,"merchant",environment,"store","channel",false,10,
                Duration.ofSeconds(1),Duration.ofSeconds(2),false,Duration.ofHours(1),7);
    }
    void fails() {
        assertThatThrownBy(() -> client().fetch(query())).isInstanceOfSatisfying(BusinessException.class,
                error -> {assertThat(error.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);assertThat(error.getMessage()).doesNotContain(KEY);});
    }
}
