package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class SearchSellerMonths {
    private final SellerMonthlySettlementRepository months;
    public SearchSellerMonths(SellerMonthlySettlementRepository months) { this.months=months; }
    public Page<SellerMonthlySettlement> execute(SettlementQuery query) {
        return months.search(query.sellerSubject(),query.month(),PageRequest.of(query.page(),query.size(),Sort.by("settlementMonth","id").descending()));
    }
}
