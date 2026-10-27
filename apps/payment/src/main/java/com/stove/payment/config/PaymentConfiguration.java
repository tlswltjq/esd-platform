package com.stove.payment.config;

import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.payment.adapter.pg.SimulatedPgHttpGateway;
import com.stove.payment.adapter.trace.DisabledPaymentTracing;
import com.stove.payment.adapter.trace.JdbcPaymentTraceContexts;
import com.stove.payment.adapter.trace.MicrometerPaymentTracing;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.core.port.PaymentTraceContexts;
import com.stove.payment.core.port.PaymentTracing;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.service.ReceiveOrderPayment;
import jakarta.persistence.EntityManager;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {
    @Bean
    ResolvePayment resolvePayment(PaymentRepository payments, PgGateway gateway, OutboxRecorder outbox,
            EntityManager entityManager, PlatformTransactionManager transactions, PaymentProcessingProperties properties,
            PaymentTraceContexts traceContexts, PaymentTracing tracing, PgHttpProperties pg) {
        return new ResolvePayment(payments, gateway, outbox, entityManager, transactions,
                properties.batchSize(), Duration.ofMillis(properties.retryDelayMs()), traceContexts, tracing, pg.provider());
    }

    @Bean
    @ConditionalOnProperty(name = "stove.payment.pg.provider", havingValue = "SIMULATED", matchIfMissing = true)
    SimulatedPgHttpGateway simulatedPgGateway(PgHttpProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout()); factory.setReadTimeout(properties.readTimeout());
        return new SimulatedPgHttpGateway(builder.clone().baseUrl(properties.baseUrl().toString())
                .defaultHeader("X-Pg-Api-Key",properties.apiKey()).requestFactory(factory).build());
    }

    @Bean
    ReceiveOrderPayment receiveOrderPayment(PaymentRepository payments, ProcessedEventGuard guard, PgHttpProperties properties,
            PaymentTraceContexts traceContexts) {
        return new ReceiveOrderPayment(payments,guard,properties.provider(),traceContexts);
    }

    @Bean
    PaymentTraceContexts paymentTraceContexts(JdbcTemplate jdbc) {
        return new JdbcPaymentTraceContexts(jdbc);
    }

    @Bean
    PaymentTracing paymentTracing(ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators) {
        Tracer tracer = tracers.getIfAvailable();
        Propagator propagator = propagators.getIfAvailable();
        return tracer == null || propagator == null
                ? new DisabledPaymentTracing() : new MicrometerPaymentTracing(tracer, propagator);
    }
}
