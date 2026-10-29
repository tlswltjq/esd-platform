package com.stove.payment.config;

import com.stove.payment.adapter.crypto.AesGcmApprovalPayloadProtection;
import com.stove.payment.adapter.order.HttpOrderPaymentAdmissions;
import com.stove.payment.adapter.pg.AdmittedPortOnePgGateway;
import com.stove.payment.adapter.pg.PortOnePaymentApprovalGateway;
import com.stove.payment.core.domain.PaymentApprovalRepository;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentOrderLifecycleRepository;
import com.stove.payment.core.port.ApprovalPayloadProtection;
import com.stove.payment.core.port.OrderPaymentAdmissions;
import com.stove.payment.core.port.PgGateway;
import com.stove.payment.core.port.PgPaymentApprovalGateway;
import com.stove.payment.core.service.RequestPaymentApproval;
import com.stove.payment.core.service.ReadPayment;
import com.stove.payment.core.service.ReadPaymentApproval;
import com.stove.payment.core.service.ResolvePayment;
import com.stove.payment.core.service.ResolvePaymentApproval;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="stove.payment.pg.provider",havingValue="PORTONE_TEST")
public class PaymentApprovalConfiguration {
    @Bean ApprovalPayloadProtection approvalPayloadProtection(PaymentApprovalProperties properties) {
        properties.requireConfigured();
        return new AesGcmApprovalPayloadProtection(properties.decodedPayloadKey());
    }
    @Bean OrderPaymentAdmissions orderPaymentAdmissions(PaymentApprovalProperties properties,RestClient.Builder builder) {
        properties.requireConfigured();
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.orderConnectTimeout()); factory.setReadTimeout(properties.orderReadTimeout());
        return new HttpOrderPaymentAdmissions(builder.clone().baseUrl(properties.orderBaseUrl().toString())
                .defaultHeader("X-Payment-Service-Key",properties.orderServiceKey()).requestFactory(factory).build());
    }
    @Bean PgPaymentApprovalGateway pgPaymentApprovalGateway(PortOneProperties properties,RestClient.Builder builder,Clock refundReceptionClock) {
        properties.requireConfigured();
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout()); factory.setReadTimeout(properties.readTimeout());
        RestClient client=builder.clone().baseUrl(properties.baseUrl().toString()).defaultHeader("Authorization","PortOne "+properties.apiSecret())
                .requestFactory(factory).build();
        return new PortOnePaymentApprovalGateway(client,properties.storeId(),properties.channelKey(),refundReceptionClock);
    }
    @Bean PgGateway admittedPortOnePgGateway(PaymentApprovalRepository approvals,PgPaymentApprovalGateway gateway) {
        return new AdmittedPortOnePgGateway(approvals,gateway);
    }
    @Bean ReadPaymentApproval readPaymentApproval(ReadPayment payments,PaymentApprovalRepository approvals) {
        return new ReadPaymentApproval(payments,approvals);
    }
    @Bean RequestPaymentApproval requestPaymentApproval(PaymentRepository payments,PaymentApprovalRepository approvals,
            PgPaymentApprovalGateway gateway,ApprovalPayloadProtection protection,Clock refundReceptionClock,PlatformTransactionManager transactions) {
        return new RequestPaymentApproval(payments,approvals,gateway,protection,refundReceptionClock,transactions);
    }
    @Bean ResolvePaymentApproval resolvePaymentApproval(PaymentRepository payments,PaymentApprovalRepository approvals,
            OrderPaymentAdmissions orders,PgPaymentApprovalGateway gateway,ApprovalPayloadProtection protection,ResolvePayment resolve,
            Clock refundReceptionClock,PortOneProperties pg,PaymentApprovalProperties properties,PaymentProcessingProperties processing,
            PlatformTransactionManager transactions,PaymentOrderLifecycleRepository lifecycle) {
        Duration lease=pg.connectTimeout().plus(pg.readTimeout()).multipliedBy(3)
                .plus(properties.orderConnectTimeout()).plus(properties.orderReadTimeout()).plusSeconds(30);
        return new ResolvePaymentApproval(payments,approvals,orders,gateway,protection,resolve,refundReceptionClock,
                lease,Duration.ofMillis(processing.retryDelayMs()),processing.batchSize(),transactions,lifecycle);
    }
    @Configuration(proxyBeanMethods=false)
    @EnableScheduling
    @ConditionalOnProperty(name="stove.payment.processing-enabled",havingValue="true",matchIfMissing=true)
    static class Scheduling {
        @Bean ThreadPoolTaskScheduler paymentApprovalTaskScheduler() {
            ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("payment-approval-"); return scheduler;
        }

    }
}
