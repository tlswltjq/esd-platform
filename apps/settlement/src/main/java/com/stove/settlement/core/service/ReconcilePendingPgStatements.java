package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementRepository;
import lombok.RequiredArgsConstructor;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;

/** Bounded replay of durable observations, including dates outside collection lookback. */
@Slf4j @RequiredArgsConstructor
public class ReconcilePendingPgStatements implements Runnable {
    private final PgStatementRepository statements;
    private final ReconcilePgStatement reconcile;
    private long afterId;
    @Override public void run() {
        List<PgStatement> batch=statements.pendingAfter(afterId,PageRequest.of(0,100));
        for(PgStatement statement:batch) {
            afterId=statement.getId();
            try {reconcile.execute(statement.getId());}
            catch(RuntimeException failure) {log.warn("PG statement reconciliation will retry: id={}, failureType={}",statement.getId(),failure.getClass().getSimpleName());}
        }
        if(batch.size()<100) afterId=0;
    }
}
