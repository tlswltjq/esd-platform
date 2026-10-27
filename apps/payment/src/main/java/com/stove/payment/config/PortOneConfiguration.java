package com.stove.payment.config;

import com.stove.payment.adapter.pg.HmacPortOneWebhookVerifier;
import com.stove.payment.adapter.pg.JdbcPortOneWebhookReceipts;
import com.stove.payment.adapter.pg.PortOnePgHttpGateway;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.port.PortOneWebhookReceipts;
import com.stove.payment.core.port.PortOneWebhookVerifier;
import com.stove.payment.core.service.ReceivePortOneWebhook;
import com.stove.payment.core.service.ReadPayment;
import com.stove.payment.core.service.ReadPaymentCheckout;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "PORTONE_TEST")
public class PortOneConfiguration {
    @Bean PortOnePgHttpGateway portOneGateway(PortOneProperties properties, RestClient.Builder builder) {
        properties.requireConfigured();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        RestClient client = builder.clone().baseUrl(properties.baseUrl().toString())
                .defaultHeader("Authorization", "PortOne " + properties.apiSecret()).requestFactory(factory).build();
        return new PortOnePgHttpGateway(client, properties.storeId(), properties.channelKey());
    }

    @Bean PortOneWebhookVerifier portOneWebhookVerifier(PortOneProperties properties, Clock refundReceptionClock) {
        properties.requireConfigured();
        return new HmacPortOneWebhookVerifier(properties.webhookSecret(), refundReceptionClock, Duration.ofMinutes(5));
    }

    @Bean PortOneWebhookReceipts portOneWebhookReceipts(JdbcTemplate jdbc) { return new JdbcPortOneWebhookReceipts(jdbc); }

    @Bean ReadPaymentCheckout readPaymentCheckout(ReadPayment readPayment, PortOneProperties properties) {
        properties.requireConfigured();
        return new ReadPaymentCheckout(readPayment, properties.storeId(), properties.channelKey(), properties.webhookUrl());
    }

    @Bean ReceivePortOneWebhook receivePortOneWebhook(PaymentRepository payments, PortOneWebhookReceipts receipts,
            Clock refundReceptionClock) {
        return new ReceivePortOneWebhook(payments, receipts, refundReceptionClock);
    }
}
