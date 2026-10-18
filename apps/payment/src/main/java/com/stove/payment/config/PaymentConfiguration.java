package com.stove.payment.config;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.adapter.pg.SimulatedPgHttpGateway;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.service.ReceiveOrderPayment;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    @Bean
    ResolvePayment resolvePayment(PaymentRepository payments, PgGateway gateway, OutboxRecorder outbox,
            EntityManager entityManager, PlatformTransactionManager transactions, PaymentProcessingProperties properties) {
        return new ResolvePayment(payments, gateway, outbox, entityManager, transactions,
                properties.batchSize(), Duration.ofMillis(properties.retryDelayMs()));
    }

    @Bean
    SimulatedPgHttpGateway simulatedPgGateway(PgHttpProperties properties) {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout()); factory.setReadTimeout(properties.readTimeout());
        return new SimulatedPgHttpGateway(RestClient.builder().baseUrl(properties.baseUrl().toString())
                .defaultHeader("X-Pg-Api-Key",properties.apiKey()).requestFactory(factory).build());
    }

    @Bean
    ReceiveOrderPayment receiveOrderPayment(PaymentRepository payments, ProcessedEventGuard guard, PgHttpProperties properties) {
        return new ReceiveOrderPayment(payments,guard,properties.provider());
    }
}
