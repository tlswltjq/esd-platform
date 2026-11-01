package com.stove.settlement.core.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PgStatementReport(PgStatementScope scope,PgStatementQuery query,List<PgStatementPage> pages,
        List<PgStatementRow> rows,PgStatementTotals totals) {
    public PgStatementReport {
        SettlementValues.require(scope!=null && query!=null && pages!=null && !pages.isEmpty() && rows!=null && totals!=null,
                "전체 PG 명세와 원본 페이지가 필요합니다.");
        pages=List.copyOf(pages);rows=List.copyOf(rows);
        String cursor=null;Set<String> cursors=new HashSet<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<pages.size();i++) {
            PgStatementPage page=pages.get(i);
            SettlementValues.require(Objects.equals(cursor,page.getRequestCursor()) && (cursor==null || cursors.add(cursor)),"PG 명세 커서가 순환하거나 누락됐습니다.");
            cursor=page.getNextCursor();
            SettlementValues.require(i==pages.size()-1 ? cursor.isEmpty() : !cursor.isEmpty(),"PG 명세의 마지막 페이지가 올바르지 않습니다.");
        }
        for(PgStatementRow row:rows) SettlementValues.require(ids.add(row.transactionId())
                && (!"DUE".equals(query.dateType()) || row.dueDate().equals(query.baseDate())),"명세 거래가 중복되거나 지급 조회일과 다릅니다.");
        SettlementValues.require(totals.equals(PgStatementTotals.sum(rows)),"전체 페이지와 공급자 합계가 다릅니다.");
        SettlementValues.require(!"TEST".equals(scope.environment()) || rows.isEmpty(),"TEST 거래에는 실제 정산 명세를 반영할 수 없습니다.");
    }
}
