package com.stove.archfixture.precision.core.service;

import org.springframework.transaction.support.TransactionTemplate;

public class ApplyRows {
    public String execute(TransactionTemplate transactions) { return transactions.execute(status -> "done"); }
}
