package com.stove.archfixture.precision.api.scheduler;

import org.springframework.transaction.support.TransactionOperations;

public class TransactionScheduler {
    public void run(TransactionOperations transactions) { transactions.executeWithoutResult(status -> { }); }
}
