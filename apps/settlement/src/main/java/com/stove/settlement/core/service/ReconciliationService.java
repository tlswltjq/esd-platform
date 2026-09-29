package com.stove.settlement.core.service;

import com.stove.settlement.core.domain.ReconciliationResult;
import com.stove.settlement.core.domain.SellerSettlement;
import com.stove.settlement.core.domain.SellerSettlementRepository;
import com.stove.settlement.core.domain.SettlementRecord;
import com.stove.settlement.core.domain.SettlementRecordRepository;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationService {
    private final SettlementRecordRepository records;
    private final SellerSettlementRepository closings;

    public List<ReconciliationResult> month(YearMonth month) {
        List<SettlementRecord> ledger = records.findBySettlementMonth(month.toString());
        Map<Long, List<SettlementRecord>> bySeller = ledger.stream()
                .collect(Collectors.groupingBy(SettlementRecord::getSellerId));
        Map<Long, SellerSettlement> byClosing = closings.findBySettlementMonth(month.toString()).stream()
                .collect(Collectors.toMap(SellerSettlement::getSellerId, closing -> closing));
        return java.util.stream.Stream.concat(bySeller.keySet().stream(), byClosing.keySet().stream())
                .distinct().sorted().map(id -> result(id, month, bySeller.getOrDefault(id, List.of()),
                        byClosing.get(id))).toList();
    }

    private ReconciliationResult result(Long sellerId, YearMonth month, List<SettlementRecord> ledger,
                          SellerSettlement closing) {
        long paid = ledger.stream().mapToLong(SettlementRecord::getPaidAmount).sum();
        long list = ledger.stream().mapToLong(SettlementRecord::getListAmount).sum();
        long sellerDiscount = ledger.stream().filter(r -> "SELLER".equals(r.getDiscountBearer()))
                .mapToLong(SettlementRecord::getDiscountAmount).sum();
        long expense = ledger.stream().mapToLong(SettlementRecord::getPlatformExpense).sum();
        long basis = ledger.stream().mapToLong(SettlementRecord::getGrossAmount).sum();
        long fee = ledger.stream().mapToLong(SettlementRecord::getFeeAmount).sum();
        long payout = ledger.stream().mapToLong(SettlementRecord::getNetAmount).sum();
        long closedBasis = ledger.stream().filter(SettlementRecord::isClosed)
                .mapToLong(SettlementRecord::getGrossAmount).sum();
        long closedFee = ledger.stream().filter(SettlementRecord::isClosed)
                .mapToLong(SettlementRecord::getFeeAmount).sum();
        long closedPayout = ledger.stream().filter(SettlementRecord::isClosed)
                .mapToLong(SettlementRecord::getNetAmount).sum();
        long basisVariance = (closing == null ? 0 : closing.getGrossAmount()) - closedBasis;
        long feeVariance = (closing == null ? 0 : closing.getFeeAmount()) - closedFee;
        long payoutVariance = (closing == null ? 0 : closing.getNetAmount()) - closedPayout;
        int unclosed = (int) ledger.stream().filter(r -> !r.isClosed()).count();
        int adjustments = (int) ledger.stream().filter(r -> r.getAdjustmentForMonth() != null).count();
        List<String> warnings = new ArrayList<>();
        if (list - sellerDiscount - expense != paid || basis - expense != paid
                || basis - fee != payout) warnings.add("AMOUNT_IDENTITY_MISMATCH");
        if (basisVariance != 0 || feeVariance != 0 || payoutVariance != 0) warnings.add("CLOSING_VARIANCE");
        if (unclosed > 0) warnings.add("UNCLOSED_RECORDS");
        if (closing != null && closing.getRecordCount() != ledger.size() - unclosed)
            warnings.add("CLOSING_COUNT_VARIANCE");
        return new ReconciliationResult(sellerId, month.toString(), paid, sellerDiscount, expense, basis, fee,
                payout, ledger.size(), unclosed, adjustments, basisVariance, feeVariance,
                payoutVariance, warnings.isEmpty(), List.copyOf(warnings));
    }

    public String csv(YearMonth month) {
        StringBuilder csv = new StringBuilder("orderNo,productId,sellerId,recordType,customerPaid,sellerDiscount,platformExpense,settlementBasis,fee,sellerPayout,promotionId,adjustmentForMonth,closed\n");
        for (SettlementRecord r : records.findBySettlementMonth(month.toString()).stream()
                .sorted(java.util.Comparator.comparing(SettlementRecord::getSellerId)
                        .thenComparing(SettlementRecord::getOrderNo)
                        .thenComparing(SettlementRecord::getProductId)
                        .thenComparing(r -> r.getRecordType().name())).toList()) {
            csv.append(r.getOrderNo()).append(',').append(r.getProductId()).append(',')
                    .append(r.getSellerId()).append(',').append(r.getRecordType()).append(',')
                    .append(r.getPaidAmount()).append(',')
                    .append("SELLER".equals(r.getDiscountBearer()) ? r.getDiscountAmount() : 0).append(',')
                    .append(r.getPlatformExpense()).append(',').append(r.getGrossAmount()).append(',')
                    .append(r.getFeeAmount()).append(',').append(r.getNetAmount()).append(',')
                    .append(r.getPromotionId() == null ? "" : r.getPromotionId()).append(',')
                    .append(r.getAdjustmentForMonth() == null ? "" : r.getAdjustmentForMonth()).append(',')
                    .append(r.isClosed()).append('\n');
        }
        return csv.toString();
    }
}
