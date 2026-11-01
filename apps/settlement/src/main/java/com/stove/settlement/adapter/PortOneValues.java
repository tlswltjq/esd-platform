package com.stove.settlement.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.PgFunding;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.domain.PgRefundCancellation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.client.RestClient;

final class PortOneValues {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private PortOneValues() { }
    static JsonNode get(RestClient client, String paymentId, String storeId) throws JsonProcessingException {
        String raw=client.get().uri(uri -> uri.path("/payments/{id}").queryParam("storeId",storeId).build(paymentId))
                .exchange((request,response) -> {
                    if(!response.getStatusCode().is2xxSuccessful()) throw HttpValues.unavailable();
                    return response.bodyTo(String.class);
                });
        if(raw==null) throw HttpValues.unavailable();
        JsonNode body=JSON.readTree(raw);if(body==null || !body.isObject()) throw HttpValues.unavailable();return body;
    }
    static PgPayment original(JsonNode body,String paymentId,String storeId,String channelKey) throws JsonProcessingException {
        JsonNode channel=body.path("channel");
        if(!paymentId.equals(HttpValues.text(body,"id")) || !storeId.equals(HttpValues.text(body,"storeId"))
                || !"V2".equals(HttpValues.text(body,"version")) || !"TEST".equals(HttpValues.text(channel,"type"))
                || !channelKey.equals(HttpValues.text(channel,"key")) || !"TOSSPAY_V2".equals(HttpValues.text(channel,"pgProvider"))) throw HttpValues.unavailable();
        JsonNode custom=JSON.readTree(HttpValues.text(body,"customData"));if(custom==null || !custom.isObject()) throw HttpValues.unavailable();
        JsonNode amounts=body.path("amount");long total=HttpValues.number(amounts,"total");
        PgFunding funding=new PgFunding(total,HttpValues.number(amounts,"paid"),HttpValues.number(amounts,"discount"),storeId,channelKey);
        PgEvidence evidence=new PgEvidence("PORTONE_TEST",HttpValues.text(body,"pgTxId"),time(body,"paidAt"));
        return new PgPayment(paymentId,HttpValues.text(custom,"orderNo"),total,HttpValues.text(body,"currency"),evidence,null,funding);
    }
    static PgRefund refund(JsonNode body,PgPayment original) {
        if(!"CANCELLED".equals(HttpValues.text(body,"status")) || HttpValues.number(body.path("amount"),"cancelled")!=original.amount()
                || !body.path("cancellations").isArray()) throw HttpValues.unavailable();
        List<PgRefundCancellation> successes=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(JsonNode row:body.path("cancellations")) {
            String id=HttpValues.text(row,"id");if(!ids.add(id)) throw HttpValues.unavailable();
            String status=HttpValues.text(row,"status");
            if("SUCCEEDED".equals(status)) successes.add(new PgRefundCancellation(original.evidence().provider(),id,
                    HttpValues.number(row,"totalAmount"),row.path("cancelledAt").isMissingNode() || row.path("cancelledAt").isNull() ? null : time(row,"cancelledAt")));
            else if(!"FAILED".equals(status)) throw HttpValues.unavailable();
        }
        return new PgRefund(original,successes,time(body,"cancelledAt"));
    }
    private static Instant time(JsonNode body,String key) { return Instant.parse(HttpValues.text(body,key)).truncatedTo(ChronoUnit.MICROS); }
}
