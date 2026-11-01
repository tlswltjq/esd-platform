package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.PgFeeRecord;
import com.stove.settlement.core.domain.PgFeeRepository;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgTransactionRecord;
import com.stove.settlement.core.domain.PgTransactionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class RecordPgTransaction {
    private final PgTransactionRepository transactions;
    private final PgFeeRepository fees;

    @Transactional(propagation = Propagation.MANDATORY)
    public PgTransactionRecord execute(PgPayment payment, Instant now, String operator) {
        PgTransactionRecord transaction = transactions.lock(payment.paymentId()).orElse(null);
        if (transaction == null) transaction = transactions.saveAndFlush(PgTransactionRecord.record(payment, now));
        else transaction.requireSame(payment);
        if (payment.cost() != null) {
            PgFeeRecord fee = fees.findByPaymentId(payment.paymentId()).orElse(null);
            if (fee == null) fees.saveAndFlush(PgFeeRecord.record(payment, now, operator));
            else fee.requireSame(payment);
        }
        return transaction;
    }
}
