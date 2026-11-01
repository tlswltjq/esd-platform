package com.stove.settlement.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PgEvidence;
import com.stove.settlement.core.domain.PgFunding;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.port.PgPayments;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 원승인 조회만 수행한다. 수수료·지급 자료나 승인/취소 명령을 대신하지 않는다. */
public class PortOnePaymentHttpClient implements PgPayments {
    private final RestClient client;
    private final String storeId;
    private final String channelKey;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public PortOnePaymentHttpClient(RestClient client, String storeId, String channelKey) {
        this.client = client; this.storeId = storeId; this.channelKey = channelKey;
    }

    @Override public PgPayment require(String paymentId) {
        try {
            String raw = client.get().uri(uri -> uri.path("/payments/{id}").queryParam("storeId", storeId).build(paymentId))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw HttpValues.unavailable();
                        return response.bodyTo(String.class);
                    });
            if (raw == null) throw HttpValues.unavailable();
            JsonNode body = json.readTree(raw);
            if (body == null || !body.isObject()) throw HttpValues.unavailable();
            JsonNode channel = body.path("channel");
            if (!paymentId.equals(HttpValues.text(body, "id")) || !storeId.equals(HttpValues.text(body, "storeId"))
                    || !"V2".equals(HttpValues.text(body, "version")) || !"PAID".equals(HttpValues.text(body, "status"))
                    || !"TEST".equals(HttpValues.text(channel, "type")) || !channelKey.equals(HttpValues.text(channel, "key"))
                    || !"TOSSPAY_V2".equals(HttpValues.text(channel, "pgProvider"))) throw HttpValues.unavailable();
            JsonNode custom = json.readTree(HttpValues.text(body, "customData"));
            if (custom == null || !custom.isObject()) throw HttpValues.unavailable();
            JsonNode amounts = body.path("amount");
            long total = HttpValues.number(amounts, "total");
            if (HttpValues.number(amounts, "cancelled") != 0) throw HttpValues.unavailable();
            PgFunding funding = new PgFunding(total, HttpValues.number(amounts, "paid"), HttpValues.number(amounts, "discount"), storeId, channelKey);
            PgEvidence evidence = new PgEvidence("PORTONE_TEST", HttpValues.text(body, "pgTxId"),
                    Instant.parse(HttpValues.text(body, "paidAt")).truncatedTo(ChronoUnit.MICROS));
            return new PgPayment(paymentId, HttpValues.text(custom, "orderNo"), total, HttpValues.text(body, "currency"), evidence, null, funding);
        } catch (BusinessException | RestClientException | JsonProcessingException | IllegalArgumentException | DateTimeException failure) {
            throw HttpValues.unavailable();
        }
    }
}
