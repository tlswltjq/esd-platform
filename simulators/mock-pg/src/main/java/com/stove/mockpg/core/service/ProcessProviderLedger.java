package com.stove.mockpg.core.service;

import com.stove.mockpg.core.domain.Charge;
import com.stove.mockpg.core.domain.ChargeRequest;
import com.stove.mockpg.core.domain.ProviderException;
import com.stove.mockpg.core.domain.SettlementReport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class ProcessProviderLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Charge.Decision decision;
    private final String feePolicyId;
    private final int feeRateBps;

    public ProcessProviderLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock,
            Charge.Decision decision, String feePolicyId, int feeRateBps) {
        this.jdbc = jdbc; this.clock = clock; this.decision = decision;
        this.feePolicyId = feePolicyId; this.feeRateBps = feeRateBps;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Charge charge(ChargeRequest input) {
        try {
            return transaction.execute(tx -> {
                Charge existing = findCharge(input.paymentId());
                if (existing != null) { existing.requireRequest(input); return existing; }
                Boolean changedPolicy=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_charge WHERE pg_fee_policy_id=? AND pg_fee_rate_bps<>?)",
                        Boolean.class,feePolicyId,feeRateBps);
                if(Boolean.TRUE.equals(changedPolicy)) throw new ProviderException(409,"FEE_POLICY_CONFLICT");
                boolean approved = decision == Charge.Decision.APPROVED;
                Charge result = new Charge("SIMULATED", input.paymentId(), input.orderNo(), input.amount(), input.currency(),
                        UUID.randomUUID().toString(), now(), decision, approved ? null : "DECLINED",
                        approved ? null : "모의 PG가 결제를 거절했습니다.", feePolicyId, feeRateBps,
                        approved ? Charge.fee(input.amount(), feeRateBps) : 0L);
                jdbc.update("INSERT INTO pg_charge VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", result.paymentId(), result.orderNo(),
                        result.amount(), result.currency(), result.providerTransactionId(), Timestamp.from(result.providerOccurredAt()),
                        result.decision().name(), result.reasonCode(), result.reason(), result.pgFeePolicyId(),
                        result.pgFeeRateBps(), result.pgFeeAmount());
                return result;
            });
        } catch (DataIntegrityViolationException collision) {
            Charge existing = findCharge(input.paymentId());
            if (existing == null) throw new ProviderException(409, "CHARGE_CONFLICT");
            existing.requireRequest(input); return existing;
        }
    }

    public Charge readCharge(String paymentId) {
        Charge charge = findCharge(paymentId);
        if (charge == null) throw new ProviderException(404, "CHARGE_NOT_FOUND");
        return charge;
    }

    private Charge findCharge(String paymentId) {
        List<Charge> rows = jdbc.query("SELECT * FROM pg_charge WHERE payment_id=?", ProcessProviderLedger::chargeRow, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SettlementReport run(String requestId) {
        if (requestId == null || !UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
            throw new IllegalArgumentException("Invalid request ID");
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            try { return transaction.execute(tx -> createReport(requestId)); }
            catch (DataIntegrityViolationException | TransientDataAccessException collision) {
                String existing = findRun(requestId);
                if (existing != null) return readReport(existing);
                if (attempt == 4) throw collision;
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private SettlementReport createReport(String requestId) {
        String existing = findRun(requestId);
        if (existing != null) return readReport(existing);
        Instant cutoff = now();
        List<Charge> charges = jdbc.query("""
                SELECT c.* FROM pg_charge c WHERE decision='APPROVED' AND pg_fee_amount IS NOT NULL
                AND provider_occurred_at <= ?
                AND NOT EXISTS(SELECT 1 FROM pg_settlement_item i WHERE i.payment_id=c.payment_id)
                ORDER BY provider_occurred_at, payment_id FOR UPDATE
                """, ProcessProviderLedger::chargeRow, Timestamp.from(cutoff));
        if (charges.isEmpty()) {
            existing=findRun(requestId);
            if(existing!=null) return readReport(existing);
            throw new ProviderException(409, "NO_ELIGIBLE_CHARGES");
        }
        long amount = 0; long fee = 0;
        for (Charge charge : charges) {
            amount = Math.addExact(amount, charge.amount()); fee = Math.addExact(fee, charge.pgFeeAmount());
        }
        String id = UUID.randomUUID().toString(); Instant paidAt = now();
        jdbc.update("INSERT INTO pg_settlement VALUES(?,?,?,?,?,?,?,?,?,?)", id, requestId, "KRW", Timestamp.from(cutoff),
                Timestamp.from(paidAt), "SIMULATED_PAID", amount, fee, Math.subtractExact(amount, fee), charges.size());
        for (Charge charge : charges) {
            jdbc.update("INSERT INTO pg_settlement_item VALUES(?,?,?)", charge.paymentId(), charge.providerTransactionId(), id);
        }
        return readReport(id);
    }

    public String readRun(String requestId) {
        String id = findRun(requestId);
        if (id == null) throw new ProviderException(404, "RUN_NOT_FOUND");
        return id;
    }

    private String findRun(String requestId) {
        List<String> rows = jdbc.query("SELECT provider_settlement_id FROM pg_settlement WHERE request_id=?",
                (r, n) -> r.getString(1), requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SettlementReport readReport(String id) {
        List<Charge> items = jdbc.query("""
                SELECT c.* FROM pg_settlement_item i JOIN pg_charge c ON c.payment_id=i.payment_id
                WHERE i.provider_settlement_id=? ORDER BY c.provider_occurred_at,c.payment_id
                """, ProcessProviderLedger::chargeRow, id);
        List<SettlementReport> rows = jdbc.query("SELECT * FROM pg_settlement WHERE provider_settlement_id=?",
                (r, n) -> new SettlementReport("SIMULATED", id, r.getString("currency"),
                        r.getTimestamp("cutoff_at").toInstant(), r.getTimestamp("paid_at").toInstant(), r.getString("status"),
                        r.getLong("payment_amount_total"), r.getLong("pg_fee_amount_total"), r.getLong("payout_amount"),
                        r.getLong("record_count"), items), id);
        if (rows.isEmpty()) throw new ProviderException(404, "SETTLEMENT_NOT_FOUND");
        return rows.getFirst();
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private static Charge chargeRow(ResultSet r, int index) throws SQLException {
        return new Charge("SIMULATED", r.getString("payment_id"), r.getString("order_no"), r.getLong("amount"),
                r.getString("currency"), r.getString("provider_transaction_id"), r.getTimestamp("provider_occurred_at").toInstant(),
                Charge.Decision.valueOf(r.getString("decision")), r.getString("reason_code"), r.getString("reason"),
                r.getString("pg_fee_policy_id"), r.getObject("pg_fee_rate_bps", Integer.class), r.getObject("pg_fee_amount", Long.class));
    }
}
