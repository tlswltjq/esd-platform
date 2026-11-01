package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementQuery;
import com.stove.settlement.core.domain.SellerMonthView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class SearchSellerMonths {
    private final SellerMonthlySettlementRepository months;
    private final ReadPgFinancials financials;
    public SearchSellerMonths(SellerMonthlySettlementRepository months,ReadPgFinancials financials) { this.months=months; this.financials=financials; }
    public Page<SellerMonthView> executeWithPgData(SettlementQuery query) { return financials.months(execute(query)); }
    public Page<SellerMonthlySettlement> execute(SettlementQuery query) {
        return months.search(query.sellerSubject(),query.month(),PageRequest.of(query.page(),query.size(),Sort.by("settlementMonth","id").descending()));
    }
}
