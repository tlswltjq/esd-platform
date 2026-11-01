package com.stove.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stove.settlement.core.domain.PgStatementPage;
import com.stove.settlement.core.domain.PgStatementQuery;
import com.stove.settlement.core.domain.PgStatementReport;
import com.stove.settlement.core.domain.PgStatementRow;
import com.stove.settlement.core.domain.PgStatementScope;
import com.stove.settlement.core.domain.PgStatementTotals;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class PgStatementFixtures {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PgStatementFixtures() { }
    public static PgStatementScope scope() {return new PgStatementScope("merchant-fixture","LIVE","store-fixture","channel-fixture",false);}
    public static PgStatementQuery query() {return new PgStatementQuery("DUE",LocalDate.of(2026,10,1));}
    public static PgStatementRow row(String type,String id,String token,String order,long amount,int fee,int vat) {
        return new PgStatementRow(type,id,token,order,"Fixture title",amount,fee,vat,fee+vat,"CARD",LocalDate.of(2026,10,1));
    }
    public static ObjectNode page(List<PgStatementRow> rows,String next,PgStatementTotals totals) {
        ObjectNode result=JSON.createObjectNode().put("nextCursor",next).put("totalAmount",totals.amount()).put("totalFee",totals.fee())
                .put("totalVat",totals.vat()).put("totalFeeVatSum",totals.feeVatSum());
        result.putArray("transactionList");
        for(PgStatementRow row:rows) result.withArray("transactionList").addObject().put("transactionType",row.transactionType()).put("transactionId",row.transactionId())
                .put("payToken",row.payToken()).put("orderNo",row.orderNo()).put("productDesc",row.productDesc()).put("amount",row.amount())
                .put("fee",row.fee()).put("vat",row.vat()).put("feeVatSum",row.feeVatSum()).put("payMethod",row.payMethod())
                .put("dueDate",row.dueDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        return result;
    }
    public static PgStatementReport report(PgStatementQuery query,List<PgStatementRow> rows) {
        PgStatementTotals totals=PgStatementTotals.sum(rows);
        return new PgStatementReport(scope(),query,List.of(new PgStatementPage(null,"",page(rows,"",totals).toString())),rows,totals);
    }
}
