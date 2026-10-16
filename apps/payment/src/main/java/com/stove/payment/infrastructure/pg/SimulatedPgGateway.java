package com.stove.payment.infrastructure.pg;

import com.stove.payment.core.domain.*;
import com.stove.payment.core.port.PgGateway;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;

/** Supplier-owned durable decisions, committed independently of Payment's result transaction. */
public class SimulatedPgGateway implements PgGateway {
    private final JdbcTemplate jdbc;
    private final PgResult.Decision decision;
    private final TransactionTemplate transaction;

    public SimulatedPgGateway(JdbcTemplate jdbc,PgResult.Decision decision,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.decision=decision;
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public PgResult charge(PgChargeRequest request) {
        try { return transaction.execute(tx -> {
            PgResult existing=find(request.paymentId());
            if (existing!=null) { existing.requireRequest(request); return existing; }
            boolean approved=decision==PgResult.Decision.APPROVED;
            PgResult result=new PgResult("SIMULATED",request.paymentId(),request.orderNo(),request.amount(),request.currency(),
                    UUID.randomUUID().toString(),java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),decision,
                    approved ? null : "DECLINED",approved ? null : "모의 PG가 결제를 거절했습니다.");
            jdbc.update("INSERT INTO simulated_payment_charge(payment_id,order_no,amount,currency,transaction_id,occurred_at,decision,reason_code,reason) VALUES(?,?,?,?,?,?,?,?,?)",
                    result.paymentId(),result.orderNo(),result.amount(),result.currency(),result.transactionId(),
                    Timestamp.from(result.occurredAt()),result.decision().name(),result.reasonCode(),result.reason());
            return result;
        }); } catch (DuplicateKeyException collision) {
            return transaction.execute(tx -> {
                PgResult existing=find(request.paymentId());
                if (existing==null) throw collision;
                existing.requireRequest(request); return existing;
            });
        }
    }

    private PgResult find(String paymentId) {
        List<PgResult> rows=jdbc.query("SELECT * FROM simulated_payment_charge WHERE payment_id=?",(r,n) ->
                new PgResult("SIMULATED",r.getString("payment_id"),r.getString("order_no"),r.getLong("amount"),r.getString("currency"),
                        r.getString("transaction_id"),r.getTimestamp("occurred_at").toInstant(),
                        PgResult.Decision.valueOf(r.getString("decision")),r.getString("reason_code"),r.getString("reason")),paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
