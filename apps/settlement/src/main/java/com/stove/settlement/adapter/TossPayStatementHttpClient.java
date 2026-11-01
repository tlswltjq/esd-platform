package com.stove.settlement.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PgStatementPage;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementTotals;
import com.stove.settlement.core.port.PgStatementSource;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** This provider uses POST for a read-only report query, never a payment or refund command. */
public class TossPayStatementHttpClient implements PgStatementSource {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RestClient client;
    private final String apiKey;
    private final PgStatementScope scope;
    private final int maxPages;
    public TossPayStatementHttpClient(RestClient client,String apiKey,PgStatementScope scope,int maxPages) {
        this.client=client;this.apiKey=apiKey;this.scope=scope;this.maxPages=maxPages;
    }
    @Override public PgStatementReport fetch(PgStatementQuery query) {
        try {
            List<PgStatementPage> pages=new ArrayList<>();List<PgStatementRow> rows=new ArrayList<>();Set<String> cursors=new HashSet<>();
            String cursor=null;PgStatementTotals expected=null;
            do {
                if(pages.size()>=maxPages || cursor!=null && !cursors.add(cursor)) throw HttpValues.unavailable();
                Map<String,Object> request=new HashMap<>();request.put("apiKey",apiKey);request.put("dateType",query.dateType());
                request.put("baseDate",query.baseDate().format(DateTimeFormatter.BASIC_ISO_DATE));if(cursor!=null) request.put("nextCursor",cursor);
                String raw=client.post().uri("/api/v2/settlement-details").contentType(MediaType.APPLICATION_JSON).body(request).exchange((sent,response) -> {
                    if(!response.getStatusCode().is2xxSuccessful()) throw HttpValues.unavailable();return response.bodyTo(String.class);
                });
                if(raw==null || raw.length()>4_000_000 || raw.contains(apiKey)) throw HttpValues.unavailable();
                JsonNode body=JSON.readTree(raw);
                if(body==null || !body.isObject() || !body.path("transactionList").isArray() || body.path("transactionList").size()>1000) throw HttpValues.unavailable();
                PgStatementTotals totals=new PgStatementTotals(HttpValues.number(body,"totalAmount"),HttpValues.number(body,"totalFee"),
                        HttpValues.number(body,"totalVat"),HttpValues.number(body,"totalFeeVatSum"));
                if(expected==null) expected=totals;else if(!expected.equals(totals)) throw HttpValues.unavailable();
                String next=HttpValues.text(body,"nextCursor");pages.add(new PgStatementPage(cursor,next,raw));cursor=next;
                for(JsonNode row:body.path("transactionList")) rows.add(row(row));
            } while(!cursor.isEmpty());
            return new PgStatementReport(scope,query,pages,rows,expected);
        } catch(JsonProcessingException | RestClientException | BusinessException | DateTimeException | ArithmeticException failure) {
            throw HttpValues.unavailable();
        }
    }
    private PgStatementRow row(JsonNode row) {
        String date=HttpValues.text(row,"dueDate");if(!date.matches("[0-9]{8}")) throw HttpValues.unavailable();
        JsonNode description=row.path("productDesc");
        if(!description.isMissingNode() && !description.isNull() && !description.isTextual()) throw HttpValues.unavailable();
        return new PgStatementRow(HttpValues.text(row,"transactionType"),HttpValues.text(row,"transactionId"),HttpValues.text(row,"payToken"),
                HttpValues.text(row,"orderNo"),description.isTextual()?description.textValue():null,HttpValues.number(row,"amount"),
                Math.toIntExact(HttpValues.number(row,"fee")),Math.toIntExact(HttpValues.number(row,"vat")),Math.toIntExact(HttpValues.number(row,"feeVatSum")),
                HttpValues.text(row,"payMethod"),LocalDate.parse(date,DateTimeFormatter.BASIC_ISO_DATE));
    }
}
