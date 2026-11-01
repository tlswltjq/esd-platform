package com.stove.archfixture.precision.adapter.oauth;

import org.springframework.transaction.support.TransactionTemplate;

public class RotatingTokens {
    public String save(TransactionTemplate transactions) { return transactions.execute(status -> "saved"); }
}
