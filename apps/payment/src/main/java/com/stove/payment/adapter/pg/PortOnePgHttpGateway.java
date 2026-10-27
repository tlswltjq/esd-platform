package com.stove.payment.adapter.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.PgChargeRequest;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.domain.PgUnavailable;
import com.stove.payment.core.port.PgGateway;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class PortOnePgHttpGateway implements PgGateway {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public PortOnePgHttpGateway(RestClient client, String storeId, String channelKey) {
        this.client = client;
        this.storeId = storeId;
        this.channelKey = channelKey;
    }

    @Override
    public PgResult charge(PgChargeRequest request) {
        try {
            JsonNode body = client.get().uri(uri -> uri.path("/payments/{id}").queryParam("storeId", storeId)
                    .build(request.paymentId())).exchange((outgoing, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw new PgUnavailable("PortOne lookup unavailable");
                        return response.bodyTo(JsonNode.class);
                    });
            if (body == null || !body.isObject()) throw new IllegalArgumentException("Missing PortOne payment");
            if (!"PAID".equals(text(body, "status"))) throw new PgUnavailable("PortOne payment is not confirmed paid");
            JsonNode channel = body.path("channel");
            JsonNode amount = body.path("amount");
            JsonNode custom = mapper.readTree(text(body, "customData"));
            if (!request.paymentId().equals(text(body, "id")) || !storeId.equals(text(body, "storeId"))
                    || !"V2".equals(text(body, "version")) || !"KRW".equals(text(body, "currency"))
                    || !"TEST".equals(text(channel, "type")) || !channelKey.equals(text(channel, "key"))
                    || !"TOSSPAY_V2".equals(text(channel, "pgProvider"))
                    || number(amount, "total") != request.amount() || number(amount, "paid") != request.amount()
                    || number(amount, "discount") != 0 || number(amount, "cancelled") != 0
                    || custom == null || !request.orderNo().equals(text(custom, "orderNo"))) {
                throw new IllegalArgumentException("PortOne payment does not match the purchase");
            }
            return new PgResult("PORTONE_TEST", request.paymentId(), request.orderNo(), request.amount(), request.currency(),
                    text(body, "pgTxId"), Instant.parse(text(body, "paidAt")).truncatedTo(ChronoUnit.MICROS),
                    PgResult.Decision.APPROVED, null, null, null);
        } catch (RestClientException unavailable) {
            throw new PgUnavailable("PortOne HTTP lookup failed");
        } catch (JsonProcessingException | DateTimeException invalid) {
            throw new IllegalArgumentException("Invalid PortOne purchase evidence");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Missing PortOne field");
        return value.textValue();
    }

    private static long number(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid PortOne amount");
        return value.longValue();
    }
}
