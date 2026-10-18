package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.SettlementQuery;
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
    public SearchSales(SettlementSaleRepository sales) { this.sales=sales; }
    public Page<SettlementSale> execute(SettlementQuery query) {
        return sales.search(query.sellerSubject(),query.month(),PageRequest.of(query.page(),query.size(),Sort.by("occurredAt","id").descending()));
    }
}
