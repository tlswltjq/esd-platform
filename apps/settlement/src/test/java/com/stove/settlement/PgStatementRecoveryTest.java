package com.stove.settlement;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.service.ReconcilePendingPgStatements;
import com.stove.settlement.core.service.ReconcilePgStatement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class PgStatementRecoveryTest {
    @Test void aFailedStoredObservationIsRetriedOnTheNextBoundedPass() {
        PgStatementRepository statements=mock(PgStatementRepository.class);
        ReconcilePgStatement reconcile=mock(ReconcilePgStatement.class);PgStatement statement=mock(PgStatement.class);
        when(statement.getId()).thenReturn(10L);
        when(statements.pendingAfter(0,PageRequest.of(0,100))).thenReturn(List.of(statement));
        doThrow(new IllegalStateException("fixture failure")).when(reconcile).execute(10L);
        ReconcilePendingPgStatements recovery=new ReconcilePendingPgStatements(statements,reconcile);
        recovery.run();recovery.run();verify(reconcile,times(2)).execute(10L);
    }
}
