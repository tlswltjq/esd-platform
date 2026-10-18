package com.stove.settlement.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgPayment;
import java.time.Instant;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class HttpValues {
    private HttpValues() { }
    static JsonNode get(RestClient client,String path,String id) {
        try {
            JsonNode body=client.get().uri(path,id).retrieve().onStatus(s -> !s.is2xxSuccessful(),(r,s) -> {
                if(s.getStatusCode().value()==404) throw new BusinessException(ErrorCode.NOT_FOUND);
                throw unavailable();
            }).body(JsonNode.class);
            if(body==null || !body.isObject()) throw unavailable();
            return body;
        } catch(RestClientException failure) { throw unavailable(); }
    }
    static String text(JsonNode body,String key) {
        JsonNode value=body.path(key);
        if(!value.isTextual()) throw unavailable();
        return value.textValue();
    }
    static long number(JsonNode body,String key) {
        JsonNode value=body.path(key);
        if(!value.isIntegralNumber() || !value.canConvertToLong()) throw unavailable();
        return value.longValue();
    }
    static PgPayment payment(JsonNode body) {
        if(!"APPROVED".equals(text(body,"decision"))) throw unavailable();
        return new PgPayment(text(body,"paymentId"),text(body,"orderNo"),number(body,"amount"),text(body,"currency"),
                new PgCost(text(body,"provider"),text(body,"providerTransactionId"),Instant.parse(text(body,"providerOccurredAt")),
                        text(body,"pgFeePolicyId"),Math.toIntExact(number(body,"pgFeeRateBps")),number(body,"pgFeeAmount")));
    }
    static BusinessException unavailable() { return new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE); }
}
