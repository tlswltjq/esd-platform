package com.stove.archfixture.precision.api.listener;

import org.springframework.transaction.PlatformTransactionManager;

public class TransactionListener {
    public void receive(PlatformTransactionManager manager) { manager.getTransaction(null); }
}
