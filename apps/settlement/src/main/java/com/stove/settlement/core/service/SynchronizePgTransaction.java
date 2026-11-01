package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgFeeRecord;
import com.stove.settlement.core.domain.PgFeeRepository;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgTransactionRecord;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.PgTransactionView;
import com.stove.settlement.core.port.PgPayments;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class SynchronizePgTransaction {
    private final PgTransactionRepository transactions;
    private final PgFeeRepository fees;
    private final PgPayments supplier;
    private final RecordPgTransaction record;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SynchronizePgTransaction(PgTransactionRepository transactions, PgFeeRepository fees, PgPayments supplier,
                                RecordPgTransaction record, Clock clock, PlatformTransactionManager manager) {
        this.transactions = transactions; this.fees = fees; this.supplier = supplier; this.record = record; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PgTransactionView execute(String paymentId, String operator) {
        if (paymentId == null || paymentId.isBlank() || paymentId.length() > 64 || operator == null || operator.isBlank() || operator.length() > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PG 조회 중 업무 트랜잭션을 유지할 수 없습니다.");
        PgTransactionRecord expected = transactions.findByPaymentId(paymentId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        PgPayment observed = supplier.require(paymentId);
        expected.requireSame(observed);
        return transaction.execute(tx -> {
            PgTransactionRecord fact = record.execute(observed, clock.instant().truncatedTo(ChronoUnit.MICROS), operator);
            PgFeeRecord fee = fees.findByPaymentId(paymentId).orElse(null);
            return new PgTransactionView(fact.payment(fee == null ? null : fee.getCost()), fact.getReceivedAt(), fee == null ? null : fee.getReceivedAt());
        });
    }
}
