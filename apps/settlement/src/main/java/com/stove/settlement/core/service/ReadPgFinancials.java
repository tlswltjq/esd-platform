package com.stove.settlement.core.service;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgFeeGroup;
import com.stove.settlement.core.domain.PgFeeRecord;
import com.stove.settlement.core.domain.PgFeeRepository;
import com.stove.settlement.core.domain.PgFeeTotals;
import com.stove.settlement.core.domain.PgTransactionRecord;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthView;
import com.stove.settlement.core.domain.SellerMonthKey;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleView;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ReadPgFinancials {
    private final PgTransactionRepository transactions;
    private final PgFeeRepository fees;

    public Page<SettlementSaleView> sales(Page<SettlementSale> page) {
        if (page.isEmpty()) return new PageImpl<>(List.of(),page.getPageable(),page.getTotalElements());
        List<String> ids = page.getContent().stream().map(SettlementSale::getPaymentId).toList();
        Map<String, PgTransactionRecord> facts = transactions.findByPaymentIdIn(ids).stream()
                .collect(Collectors.toMap(PgTransactionRecord::getPaymentId, record -> record));
        Map<String, PgCost> costs = fees.findByPaymentIdIn(ids).stream()
                .collect(Collectors.toMap(PgFeeRecord::getPaymentId, PgFeeRecord::getCost));
        return page.map(sale -> {
            PgTransactionRecord fact = facts.get(sale.getPaymentId());
            if (fact == null) throw new BusinessException(ErrorCode.CONFLICT, "매출의 PG 거래 근거가 없습니다.");
            fact.requireSale(sale);
            return new SettlementSaleView(sale, fact.evidence(), costs.get(sale.getPaymentId()), fact.getFunding());
        });
    }

    public Page<SellerMonthView> months(Page<SellerMonthlySettlement> page) {
        if (page.isEmpty()) return new PageImpl<>(List.of(),page.getPageable(),page.getTotalElements());
        Map<SellerMonthKey, PgFeeTotals> totals = summaries(page.getContent());
        return page.map(month -> new SellerMonthView(month, totals.getOrDefault(
                new SellerMonthKey(month.getSellerSubject(), month.getSettlementMonth()), new PgFeeTotals(0, 0, 0))));
    }

    public SellerMonthView month(SellerMonthlySettlement month) {
        Map<SellerMonthKey, PgFeeTotals> totals = summaries(List.of(month));
        return new SellerMonthView(month, totals.getOrDefault(
                new SellerMonthKey(month.getSellerSubject(), month.getSettlementMonth()), new PgFeeTotals(0, 0, 0)));
    }

    private Map<SellerMonthKey, PgFeeTotals> summaries(List<SellerMonthlySettlement> months) {
        List<String> sellers = months.stream().map(SellerMonthlySettlement::getSellerSubject).distinct().toList();
        List<String> periods = months.stream().map(SellerMonthlySettlement::getSettlementMonth).distinct().toList();
        return fees.summarize(sellers, periods).stream().collect(Collectors.toMap(
                group -> new SellerMonthKey(group.sellerSubject(), group.settlementMonth()), PgFeeGroup::totals));
    }

}
