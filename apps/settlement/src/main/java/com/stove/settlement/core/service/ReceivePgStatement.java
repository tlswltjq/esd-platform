package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementEntry;
import com.stove.settlement.core.domain.PgStatementEntryRepository;
import com.stove.settlement.core.domain.PgStatementItem;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.port.PgStatementSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ReceivePgStatement {
    private final PgStatementSource source;
    private final PgStatementRepository statements;
    private final PgStatementEntryRepository entries;
    private final PgStatementItemRepository items;
    private final ReconcilePgStatement reconcile;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public ReceivePgStatement(PgStatementSource source,PgStatementRepository statements,PgStatementEntryRepository entries,
            PgStatementItemRepository items,ReconcilePgStatement reconcile,Clock clock,PlatformTransactionManager manager) {
        this.source=source;this.statements=statements;this.entries=entries;this.items=items;this.reconcile=reconcile;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PgStatement execute(PgStatementQuery query,String operator) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PG 명세 조회 중 업무 트랜잭션을 유지할 수 없습니다.");
        if(query==null || operator==null || operator.isBlank() || operator.length()>100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        LocalDate today=clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        if(query.baseDate().isAfter(today) || query.baseDate().isBefore(today.minusYears(1))
                || "SETTLE".equals(query.dateType()) && query.baseDate().equals(today)) throw new BusinessException(ErrorCode.INVALID_REQUEST,"적재 가능한 지난 거래일 또는 지급일을 지정하세요.");
        if("DUE".equals(query.dateType()) && query.baseDate().equals(today)
                && clock.instant().atZone(ZoneId.of("Asia/Seoul")).getHour()<10) throw new BusinessException(ErrorCode.CONFLICT,"당일 지급 자료는 KST 10시 이후 수집합니다.");
        PgStatementReport report=source.fetch(query);
        if(report==null || !query.equals(report.query())) throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        Long id=null;
        for(int attempt=0;attempt<5;attempt++) {
            try {id=transaction.execute(tx -> record(report,operator).getId());break;}
            catch(DataIntegrityViolationException | TransientDataAccessException collision) {
                if(attempt==4) throw new BusinessException(ErrorCode.CONFLICT,"PG 명세 수집 키가 충돌했습니다.");
            }
        }
        if(id==null) throw new IllegalStateException("No statement collected");
        return reconcile.execute(id);
    }
    private PgStatement record(PgStatementReport report,String operator) {
        PgStatement previous=statements.findByFingerprint(PgStatement.fingerprint(report)).orElse(null);
        if(previous!=null) return previous;
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        PgStatement statement=statements.saveAndFlush(PgStatement.record(report,operator,now));
        int position=0;
        for(PgStatementRow row:report.rows()) {
            PgStatementEntry entry=entries.findByMerchantIdAndEnvironmentAndTransactionId(report.scope().merchantId(),report.scope().environment(),row.transactionId()).orElse(null);
            if(entry==null) entry=entries.saveAndFlush(PgStatementEntry.record(report.scope(),row,now));
            else entry.requireSame(row);
            items.save(PgStatementItem.record(statement.getId(),position++,entry));
        }
        items.flush();return statement;
    }
}
