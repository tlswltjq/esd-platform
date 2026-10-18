package com.stove.payment.adapter.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgFee;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PgGateway;
import java.time.Instant;
import java.time.DateTimeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class SimulatedPgHttpGateway implements PgGateway {
    private final RestClient client;

    public SimulatedPgHttpGateway(RestClient client) { this.client=client; }

    @Override public PgResult charge(PgChargeRequest request) {
        try {
            JsonNode body=client.get().uri("/v1/charges/by-reference/{id}",request.paymentId())
                    .exchange((r,s) -> {
                        if (s.getStatusCode().value()==404) return null;
                        if (!s.getStatusCode().is2xxSuccessful()) throw new PgUnavailable("PG lookup unavailable");
                        JsonNode found=s.bodyTo(JsonNode.class);
                        if(found==null || !found.isObject()) throw new IllegalArgumentException("Missing PG lookup body");
                        return found;
                    });
            if (body==null) body=client.post().uri("/v1/charges").header("Idempotency-Key",request.paymentId())
                    .body(request).retrieve().body(JsonNode.class);
            PgFee fee=fee(body);
            PgResult result=new PgResult(text(body,"provider"),text(body,"paymentId"),text(body,"orderNo"),
                    number(body,"amount"),text(body,"currency"),text(body,"providerTransactionId"),
                    Instant.parse(text(body,"providerOccurredAt")),PgResult.Decision.valueOf(text(body,"decision")),
                    optional(body,"reasonCode"),optional(body,"reason"),fee);
            result.requireRequest(request);
            return result;
        } catch (RestClientException unavailable) { throw new PgUnavailable("PG HTTP request failed"); }
        catch (DateTimeException invalid) { throw new IllegalArgumentException("Invalid PG decision time"); }
    }

    private static PgFee fee(JsonNode body) {
        String policy=optional(body,"pgFeePolicyId");
        if (policy==null && body.path("pgFeeRateBps").isNull() && body.path("pgFeeAmount").isNull()) return null;
        return new PgFee(policy,Math.toIntExact(number(body,"pgFeeRateBps")),number(body,"pgFeeAmount"));
    }

    private static String text(JsonNode body,String name) {
        String value=optional(body,name);
        if(value==null) throw new IllegalArgumentException("Missing PG response field: "+name);
        return value;
    }
    private static String optional(JsonNode body,String name) {
        if(body==null || !body.isObject()) throw new IllegalArgumentException("Invalid PG response");
        JsonNode value=body.get(name);
        if(value==null || value.isNull()) return null;
        if(!value.isTextual()) throw new IllegalArgumentException("Invalid PG text: "+name);
        return value.textValue();
    }
    private static long number(JsonNode body,String name) {
        JsonNode value=body.path(name);
        if(!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid PG number: "+name);
        return value.longValue();
    }
}
