package com.stove.payment.config;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.adapter.pg.SimulatedPgGateway;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.core.service.ResolvePayment;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    @Bean
    ResolvePayment resolvePayment(PaymentRepository payments, PgGateway gateway, OutboxRecorder outbox,
            EntityManager entityManager, PlatformTransactionManager transactions, PaymentProcessingProperties properties) {
        return new ResolvePayment(payments, gateway, outbox, entityManager, transactions,
                properties.batchSize(), Duration.ofMillis(properties.retryDelayMs()));
    }

    @Bean
    @ConditionalOnProperty(name = "stove.payment.pg-provider", havingValue = "simulated")
    SimulatedPgGateway simulatedPgGateway(JdbcTemplate jdbc, SimulatedPgProperties properties,
            PlatformTransactionManager transactions) {
        return new SimulatedPgGateway(jdbc, properties.result(), transactions);
    }
}
