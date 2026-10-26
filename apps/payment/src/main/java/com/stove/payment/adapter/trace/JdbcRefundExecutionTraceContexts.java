package com.stove.payment.adapter.trace;

import com.stove.payment.core.domain.PaymentTraceContext;
import com.stove.payment.core.port.RefundExecutionTraceContexts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcRefundExecutionTraceContexts implements RefundExecutionTraceContexts {
    private final JdbcTemplate jdbc;

    public JdbcRefundExecutionTraceContexts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void save(String attemptId, PaymentTraceContext context, Instant receivedAt) {
        jdbc.update("INSERT INTO refund_execution_trace(attempt_id,trace_parent,received_at) VALUES(?,?,?)",
                attemptId, context.traceParent(), Timestamp.from(receivedAt));
    }

    @Override public PaymentTraceContext find(String attemptId) {
        List<PaymentTraceContext> contexts = jdbc.query("SELECT trace_parent FROM refund_execution_trace WHERE attempt_id=?",
                (row, index) -> new PaymentTraceContext(row.getString(1)), attemptId);
        return contexts.isEmpty() ? PaymentTraceContext.NONE : contexts.getFirst();
    }
}
