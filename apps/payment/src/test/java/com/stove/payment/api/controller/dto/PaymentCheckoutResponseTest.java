package com.stove.payment.api.controller.dto;

import static com.stove.payment.PaymentFixtures.id;
import static com.stove.payment.PortOneFixtures.CHANNEL;
import static com.stove.payment.PortOneFixtures.STORE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.payment.core.domain.PaymentCheckout;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentCheckoutResponseTest {
    @Test void exposesOnlySdkPublicParametersWithTheOriginalOrderAndFixedPayment() throws Exception {
        PaymentCheckout checkout = new PaymentCheckout(id(), id(), "한글 상품", 9000, "KRW", STORE, CHANNEL,
                URI.create("https://payments.example/api/v1/payments/webhooks/portone"));
        JsonNode body = new ObjectMapper().valueToTree(PaymentCheckoutResponse.from(checkout));
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("storeId", "channelKey", "paymentId", "orderName", "totalAmount",
                "currency", "payMethod", "customData", "noticeUrls");
        assertThat(body.path("paymentId").asText()).isEqualTo(checkout.paymentId());
        assertThat(body.path("orderName").asText()).isEqualTo("한글 상품");
        assertThat(body.path("totalAmount").asLong()).isEqualTo(9000);
        assertThat(body.path("currency").asText()).isEqualTo("CURRENCY_KRW");
        assertThat(body.path("payMethod").asText()).isEqualTo("EASY_PAY");
        assertThat(body.path("storeId").asText()).isEqualTo(STORE);
        assertThat(body.path("channelKey").asText()).isEqualTo(CHANNEL);
        assertThat(body.path("customData").path("orderNo").asText()).isEqualTo(checkout.orderNo());
        assertThat(body.path("noticeUrls").get(0).asText()).isEqualTo(checkout.webhookUrl().toString());
    }
}
