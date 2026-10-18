package com.stove.settlement.adapter;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.port.PgPayments;
import java.time.DateTimeException;
import org.springframework.web.client.RestClient;

public class SimulatedPgPaymentHttpClient implements PgPayments {
    private final RestClient client;
    public SimulatedPgPaymentHttpClient(RestClient client) { this.client=client; }
    @Override public PgPayment require(String id) {
        try {
            PgPayment payment=HttpValues.payment(HttpValues.get(client,"/v1/charges/by-reference/{id}",id));
            if(!id.equals(payment.paymentId())) throw HttpValues.unavailable();
            return payment;
        } catch(BusinessException | IllegalArgumentException | ArithmeticException | DateTimeException failure) { throw HttpValues.unavailable(); }
    }
}
