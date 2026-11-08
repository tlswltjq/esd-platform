package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.SaleWithRefund;
import com.stove.settlement.core.domain.SettlementQuery;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSaleView;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class SearchSales {
    private final SettlementSaleRepository sales;
    private final SettlementRefundRepository refunds;
    private final ReadPgFinancials financials;
    public SearchSales(SettlementSaleRepository sales,SettlementRefundRepository refunds,ReadPgFinancials financials) {
        this.sales=sales; this.refunds=refunds; this.financials=financials;
    }
    public Page<SettlementSaleView> executeWithPgData(SettlementQuery query) {
        Page<SettlementSale> page=search(query);
        Map<String,SettlementRefund> linked=refundsOf(page.getContent());
        return financials.sales(page).map(view -> view.withRefund(linked.get(view.sale().getOrderNo())));
    }
    public Page<SaleWithRefund> execute(SettlementQuery query) {
        Page<SettlementSale> page=search(query);
        Map<String,SettlementRefund> linked=refundsOf(page.getContent());
        return page.map(sale -> new SaleWithRefund(sale,linked.get(sale.getOrderNo())));
    }
    private Page<SettlementSale> search(SettlementQuery query) {
        return sales.search(query.sellerSubject(),query.month(),PageRequest.of(query.page(),query.size(),Sort.by("occurredAt","id").descending()));
    }
    /** 한 페이지의 원주문을 한 번에 조회한다. 환불 원장은 order_no 유일 제약으로 주문당 최대 1건이다. */
    private Map<String,SettlementRefund> refundsOf(List<SettlementSale> content) {
        if(content.isEmpty()) return Map.of();
        List<String> orderNos=content.stream().map(SettlementSale::getOrderNo).toList();
        return refunds.findByOrderNoIn(orderNos).stream().collect(Collectors.toMap(SettlementRefund::getOrderNo,Function.identity()));
    }
}
