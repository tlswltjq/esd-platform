package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.SellerCarryover;
import com.stove.settlement.core.domain.SellerCarryoverRepository;
import com.stove.settlement.core.domain.SellerMonthBalance;
import com.stove.settlement.core.domain.SellerMonthKey;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Joins seller months with their carry ledger rows for month responses (decisions 121·130). */
@Service @Transactional(readOnly=true)
public class ReadSellerBalances {
    private final SellerCarryoverRepository carryovers;
    public ReadSellerBalances(SellerCarryoverRepository carryovers) { this.carryovers=carryovers; }
    public Map<SellerMonthKey,SellerMonthBalance> of(List<SellerMonthlySettlement> months) {
        Map<SellerMonthKey,SellerMonthBalance> result=new HashMap<>();
        if(months.isEmpty()) return result;
        List<String> sellers=months.stream().map(SellerMonthlySettlement::getSellerSubject).distinct().toList();
        List<String> periods=months.stream().map(SellerMonthlySettlement::getSettlementMonth).distinct().toList();
        Map<SellerMonthKey,SellerCarryover> ledger=new HashMap<>();
        for(SellerCarryover row:carryovers.findAllBySellerSubjectInAndSettlementMonthIn(sellers,periods))
            ledger.put(new SellerMonthKey(row.getSellerSubject(),row.getSettlementMonth()),row);
        for(SellerMonthlySettlement month:months) {
            SellerMonthKey key=new SellerMonthKey(month.getSellerSubject(),month.getSettlementMonth());
            result.put(key,SellerMonthBalance.of(month,ledger.get(key)));
        }
        return result;
    }
    public SellerMonthBalance of(SellerMonthlySettlement month) {
        return of(List.of(month)).get(new SellerMonthKey(month.getSellerSubject(),month.getSettlementMonth()));
    }
}
