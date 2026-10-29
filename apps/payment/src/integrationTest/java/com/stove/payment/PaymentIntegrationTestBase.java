package com.stove.payment;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제 독립 PG HTTP 계약을 확인하는 테스트에서만 공급자 프로세스를 기동한다. */
public abstract class PaymentIntegrationTestBase extends PaymentDatabaseIntegrationTestBase {
    @DynamicPropertySource
    static void supplier(DynamicPropertyRegistry properties) {
        properties.add("stove.payment.pg.base-url", ExternalPgProcess::baseUrl);
        properties.add("stove.payment.pg.api-key", () -> ExternalPgProcess.PAYMENT_KEY);
    }
}
