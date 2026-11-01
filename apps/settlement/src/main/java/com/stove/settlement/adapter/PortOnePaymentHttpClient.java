package com.stove.settlement.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.port.PgPayments;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.time.DateTimeException;

/** Reads immutable approval evidence, including a fully cancelled approval received late. */
public class PortOnePaymentHttpClient implements PgPayments {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    public PortOnePaymentHttpClient(RestClient client,String storeId,String channelKey) { this.client=client;this.storeId=storeId;this.channelKey=channelKey; }
    @Override public PgPayment require(String paymentId) {
        try {
            JsonNode body=PortOneValues.get(client,paymentId,storeId);
            PgPayment original=PortOneValues.original(body,paymentId,storeId,channelKey);
            if("PAID".equals(HttpValues.text(body,"status")) && HttpValues.number(body.path("amount"),"cancelled")==0) return original;
            PortOneValues.refund(body,original);return original;
        } catch(JsonProcessingException | RestClientException | BusinessException | DateTimeException | ArithmeticException failure) {
            throw HttpValues.unavailable();
        }
    }
}
