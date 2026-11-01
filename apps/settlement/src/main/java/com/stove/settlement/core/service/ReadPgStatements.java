package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgStatement;
import com.stove.settlement.core.domain.PgStatementItem;
import com.stove.settlement.core.domain.PgStatementItemRepository;
import com.stove.settlement.core.domain.PgStatementPage;
import com.stove.settlement.core.domain.PgStatementRepository;
import com.stove.settlement.core.domain.SettlementQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ReadPgStatements {
    private final PgStatementRepository statements;
    private final PgStatementItemRepository items;
    public Page<PgStatement> execute(int page,int size) {
        new SettlementQuery(null,null,page,size);
        return statements.findAll(PageRequest.of(page,size,Sort.by("receivedAt","id").descending()));
    }
    public PgStatement execute(Long id) {
        if(id==null || id<=0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return statements.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    public Page<PgStatementItem> items(Long id,int page,int size) {
        execute(id);new SettlementQuery(null,null,page,size);
        return items.findByStatementId(id,PageRequest.of(page,size,Sort.by("itemPosition")));
    }
    public PgStatementPage page(Long id,int position) {
        PgStatement statement=execute(id);
        if(position<0 || position>=statement.getPageCount()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return statement.getPages().get(position);
    }
}
