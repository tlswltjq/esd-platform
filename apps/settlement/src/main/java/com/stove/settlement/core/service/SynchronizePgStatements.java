package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.PgStatementQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SynchronizePgStatements implements Runnable {
    private final ReceivePgStatement collect;
    private final Clock clock;
    private final int lookbackDays;
    private final Runnable recovery;
    public SynchronizePgStatements(ReceivePgStatement collect,Clock clock,int lookbackDays) {
        this(collect,clock,lookbackDays,() -> { });
    }
    public SynchronizePgStatements(ReceivePgStatement collect,Clock clock,int lookbackDays,Runnable recovery) {
        this.collect=collect;this.clock=clock;this.lookbackDays=lookbackDays;this.recovery=recovery;
    }
    @Override public void run() {
        ZonedDateTime now=clock.instant().atZone(ZoneId.of("Asia/Seoul"));
        if(now.getHour()<10) return;
        LocalDate today=now.toLocalDate();collect("DUE",today);
        for(int day=1;day<=lookbackDays;day++) {
            LocalDate date=today.minusDays(day);collect("DUE",date);collect("SETTLE",date);
        }
        recovery.run();
    }
    private void collect(String type,LocalDate date) {
        try {collect.execute(new PgStatementQuery(type,date),"SYSTEM:TOSSPAY");}
        catch(RuntimeException failure) {
            // Supplier errors may contain credentials; never log their message/body/stack here.
            log.warn("PG statement collection will retry: type={}, date={}, failureType={}",type,date,failure.getClass().getSimpleName());
        }
    }
}
