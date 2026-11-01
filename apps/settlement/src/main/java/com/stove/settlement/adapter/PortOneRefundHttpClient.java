package com.stove.settlement.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.port.PgRefunds;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.time.DateTimeException;

public class PortOneRefundHttpClient implements PgRefunds {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    public PortOneRefundHttpClient(RestClient client,String storeId,String channelKey) { this.client=client;this.storeId=storeId;this.channelKey=channelKey; }
    @Override public PgRefund require(PgPayment original,ConfirmedRefund confirmation) {
        try {
            JsonNode body=PortOneValues.get(client,original.paymentId(),storeId);
            PgPayment observed=PortOneValues.original(body,original.paymentId(),storeId,channelKey);
            PgRefund refund=PortOneValues.refund(body,observed);refund.requireOriginal(original);refund.requireConfirmation(confirmation);return refund;
        } catch(JsonProcessingException | RestClientException | BusinessException | DateTimeException | ArithmeticException failure) {
            throw HttpValues.unavailable();
        }
    }
}
