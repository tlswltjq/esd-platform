package com.stove.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.settlement.core.service.SynchronizePgStatements;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.service.ReceivePgStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PgStatementSchedulerTest {
    @Test void waitsForKstAvailabilityThenRetriesLookbackDatesDespiteOneSupplierFailure() {
        ReceivePgStatement collect=mock(ReceivePgStatement.class);
        SynchronizePgStatements early=scheduler(collect,"2026-11-02T00:59:59Z");early.run();verifyNoInteractions(collect);
        doThrow(new IllegalStateException("fixture failure")).when(collect).execute(new PgStatementQuery("DUE",LocalDate.of(2026,11,2)),"SYSTEM:TOSSPAY");
        scheduler(collect,"2026-11-02T01:00:00Z").run();
        verify(collect,times(5)).execute(any(PgStatementQuery.class),eq("SYSTEM:TOSSPAY"));
        verify(collect).execute(new PgStatementQuery("SETTLE",LocalDate.of(2026,10,31)),"SYSTEM:TOSSPAY");
    }
    SynchronizePgStatements scheduler(ReceivePgStatement collect,String instant) {
        return new SynchronizePgStatements(collect,Clock.fixed(Instant.parse(instant),ZoneOffset.UTC),2);
    }
}
