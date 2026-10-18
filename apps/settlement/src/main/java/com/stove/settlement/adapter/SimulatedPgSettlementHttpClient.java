package com.stove.settlement.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgSettlement;
import com.stove.settlement.core.port.PgSettlements;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;

public class SimulatedPgSettlementHttpClient implements PgSettlements {
    private final RestClient client;
    public SimulatedPgSettlementHttpClient(RestClient client) { this.client=client; }
    @Override public PgSettlement require(String id) {
        try {
            JsonNode body=HttpValues.get(client,"/v1/settlements/{id}",id);
            if(!body.path("items").isArray() || !id.equals(HttpValues.text(body,"providerSettlementId"))) throw HttpValues.unavailable();
            List<PgPayment> items=new ArrayList<PgPayment>();
            for(JsonNode item:body.path("items")) items.add(HttpValues.payment(item));
            return new PgSettlement(HttpValues.text(body,"provider"),id,HttpValues.text(body,"currency"),
                    Instant.parse(HttpValues.text(body,"cutoffAt")),Instant.parse(HttpValues.text(body,"paidAt")),HttpValues.text(body,"status"),
                    HttpValues.number(body,"paymentAmountTotal"),HttpValues.number(body,"pgFeeAmountTotal"),HttpValues.number(body,"payoutAmount"),
                    HttpValues.number(body,"recordCount"),items);
        } catch(BusinessException failure) {
            if(failure.errorCode()==ErrorCode.NOT_FOUND) throw failure;
            throw HttpValues.unavailable();
        } catch(IllegalArgumentException | ArithmeticException | DateTimeException failure) { throw HttpValues.unavailable(); }
    }
}
