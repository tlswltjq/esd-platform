package com.stove.settlement;

import static com.stove.settlement.PgStatementFixtures.page;
import static com.stove.settlement.PgStatementFixtures.query;
import static com.stove.settlement.PgStatementFixtures.row;
import static com.stove.settlement.PgStatementFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.adapter.TossPayStatementHttpClient;
import com.stove.settlement.config.TossPayStatementProperties;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementTotals;
import com.stove.settlement.support.TossPayStatementServer;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TossPayStatementHttpTest {
    private static final String KEY="sk_live_contract-fixture-only";
    private TossPayStatementServer server;
    private RestClient http;
    @BeforeEach void start() throws Exception {
        server=TossPayStatementServer.start();http=RestClient.create(server.baseUrl());
    }
    @AfterEach void stop() {server.close();}
    String sample(String name) throws Exception {
        try(InputStream input=getClass().getResourceAsStream("/pg/tosspay/"+name+".json")) {
            if(input==null) throw new IllegalStateException("Missing synthetic statement sample");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    TossPayStatementHttpClient client() {return new TossPayStatementHttpClient(http,KEY,scope(),10);}
    PgStatementRow pay() {return row("PAY","pay-1","token","payment",9000,-300,-30);}
    @Test void completePagesKeepRawBodiesGlobalTotalsAndReadOnlyRequestContract() throws Exception {
        PgStatementRow refund=row("REFUND","refund-1","token","payment",-9000,200,20);
        server.respond("",sample("approval-page"));server.respond("next",sample("refund-page"));
        PgStatementReport report=client().fetch(query());assertThat(report.rows()).containsExactly(pay(),refund);
        assertThat(report.pages()).hasSize(2);assertThat(report.totals().netAmount()).isEqualTo(-110);
        assertThat(report.totals()).isEqualTo(new PgStatementTotals(0,-100,-10,-110));
        assertThat(report.pages().getFirst().getResponseBody()).isEqualTo(server.responseAt(""));
        assertThat(server.requests().getFirst().path("apiKey").asText()).isEqualTo(KEY);assertThat(server.requests().getFirst().has("nextCursor")).isFalse();
        assertThat(server.requests().getFirst().path("baseDate").asText()).isEqualTo("20261001");assertThat(server.requests().get(1).path("nextCursor").asText()).isEqualTo("next");
        assertThat(server.contractErrorCount()).isZero();
    }
    @Test void incompletePagesCyclesDuplicateTransactionsAndChangingTotalsFailTheWholeRead() {
        PgStatementTotals totals=PgStatementTotals.sum(List.of(pay()));server.respond("",page(List.of(pay()),"next",totals).toString());
        server.respond("next",page(List.of(),"next",totals).toString());fails();
        server.respond("next",page(List.of(pay()),"",totals).toString());fails();
        server.respond("next",page(List.of(),"",new PgStatementTotals(0,0,0,0)).toString());fails();
        server.laterStatus(503);fails();
        assertThatThrownBy(() -> new TossPayStatementHttpClient(http,KEY,scope(),1).fetch(query())).isInstanceOf(BusinessException.class);
    }
    @Test void unknownFieldsArePreservedButCoercionBrokenRowsAndJsonAmbiguityAreRejected() {
        ObjectNode good=page(List.of(pay()),"",PgStatementTotals.sum(List.of(pay())));good.put("futureProviderField","kept");
        server.respond("",good.toString());assertThat(client().fetch(query()).pages().getFirst().getResponseBody()).contains("futureProviderField");
        List<String> invalid=new ArrayList<>();
        for(String field:List.of("amount","fee","vat","feeVatSum")) {
            ObjectNode bad=good.deepCopy();((ObjectNode)bad.path("transactionList").get(0)).put(field,"0");invalid.add(bad.toString());
        }
        ObjectNode date=good.deepCopy();((ObjectNode)date.path("transactionList").get(0)).put("dueDate","20260230");invalid.add(date.toString());
        ObjectNode unknown=good.deepCopy();((ObjectNode)unknown.path("transactionList").get(0)).put("transactionType","OTHER");invalid.add(unknown.toString());
        invalid.add(good+"{}");invalid.add(good.toString().replace("\"nextCursor\":\"\"","\"nextCursor\":\"\",\"nextCursor\":\"\""));
        invalid.add(good.deepCopy().put("echoedKey",KEY).toString());
        for(String body:invalid) {server.respond("",body);fails();}
    }
    @Test void emptyTestReportDoesNotInventZeroFeesForAnyPurchaseAndTestRowsAreRejected() {
        TossPayStatementHttpClient test=new TossPayStatementHttpClient(http,"sk_test_contract-fixture-only",new PgStatementScope("merchant","TEST","store","channel",false),10);
        server.respond("",page(List.of(),"",new PgStatementTotals(0,0,0,0)).toString());assertThat(test.fetch(query()).rows()).isEmpty();
        server.respond("",page(List.of(pay()),"",PgStatementTotals.sum(List.of(pay()))).toString());
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
