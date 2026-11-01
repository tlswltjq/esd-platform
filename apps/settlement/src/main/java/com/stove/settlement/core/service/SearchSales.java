package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.SettlementQuery;
import com.stove.settlement.core.domain.SettlementSaleView;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class SearchSales {
    private final SettlementSaleRepository sales;
    private final ReadPgFinancials financials;
    public SearchSales(SettlementSaleRepository sales,ReadPgFinancials financials) { this.sales=sales; this.financials=financials; }
    public Page<SettlementSaleView> executeWithPgData(SettlementQuery query) { return financials.sales(execute(query)); }
    public Page<SettlementSale> execute(SettlementQuery query) {
        return sales.search(query.sellerSubject(),query.month(),PageRequest.of(query.page(),query.size(),Sort.by("occurredAt","id").descending()));
    }
}
