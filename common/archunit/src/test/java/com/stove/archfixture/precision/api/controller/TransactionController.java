package com.stove.archfixture.precision.api.controller;

import org.springframework.transaction.support.TransactionTemplate;

public class TransactionController {
    public String execute(TransactionTemplate transactions) { return transactions.execute(status -> "done"); }
}
