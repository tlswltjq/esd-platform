package com.stove.payment.adapter.trace;

import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.port.PaymentTraceContexts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcPaymentTraceContexts implements PaymentTraceContexts {
    private final JdbcTemplate jdbc;

    public JdbcPaymentTraceContexts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String paymentId, PaymentTraceContext context, Instant receivedAt) {
        jdbc.update("INSERT INTO payment_request_trace(payment_id, trace_parent, received_at) VALUES (?, ?, ?)",
                paymentId, context.traceParent(), Timestamp.from(receivedAt));
    }

    @Override
    public PaymentTraceContext find(String paymentId) {
        List<PaymentTraceContext> contexts = jdbc.query(
                "SELECT trace_parent FROM payment_request_trace WHERE payment_id = ?",
                (row, index) -> new PaymentTraceContext(row.getString(1)), paymentId);
        return contexts.isEmpty() ? PaymentTraceContext.NONE : contexts.getFirst();
    }
}
