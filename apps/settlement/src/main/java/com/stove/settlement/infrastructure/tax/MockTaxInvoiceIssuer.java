package com.stove.settlement.infrastructure.tax;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockTaxInvoiceIssuer implements TaxInvoiceIssuer {

    @Override
    public String issue(Long sellerId, String settlementMonth, long netAmount) {
        String invoiceNo = "TI-%s-%06d".formatted(settlementMonth.replace("-", ""), sellerId);
        log.info("[MOCK 세금계산서] 발행 sellerId={} month={} net={} → {}",
                sellerId, settlementMonth, netAmount, invoiceNo);
        return invoiceNo;
    }
}
