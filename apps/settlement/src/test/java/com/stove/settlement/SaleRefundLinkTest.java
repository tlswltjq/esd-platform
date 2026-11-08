package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.order;
import static com.stove.settlement.SettlementFixtures.payment;
import static com.stove.settlement.SettlementRefundFixtures.confirmation;
import static com.stove.settlement.SettlementRefundFixtures.proof;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.api.controller.dto.AdminSaleResponse;
import com.stove.settlement.api.controller.dto.SaleRefundResponse;
import com.stove.settlement.api.controller.dto.SaleResponse;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.RefundMonthInput;
import com.stove.settlement.core.domain.ReverseOriginalFullRefundPolicy;
import com.stove.settlement.core.domain.SaleWithRefund;
import com.stove.settlement.core.domain.SettlementQuery;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.domain.SettlementSaleView;
import com.stove.settlement.core.domain.UseConfirmedRefundMonthPolicy;
import com.stove.settlement.core.service.ReadPgFinancials;
import com.stove.settlement.core.service.SearchSales;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** 매출 조회 응답이 원장에 저장된 환불 연결(환불 ID·확인/반영 월·사유)을 한 번의 묶음 조회로 붙이는지 확인한다. */
class SaleRefundLinkTest {
    private final SettlementSaleRepository sales = mock(SettlementSaleRepository.class);
    private final SettlementRefundRepository refunds = mock(SettlementRefundRepository.class);
    private final ReadPgFinancials financials = mock(ReadPgFinancials.class);
    private final SearchSales search = new SearchSales(sales, refunds, financials);
    private static final Instant SEPTEMBER = Instant.parse("2026-09-20T00:00:00Z");
    private final SettlementQuery query = new SettlementQuery("usr_seller", null, 0, 20);

    @Test void creatorSalesCarryTheirStoredRefundLinkFromOneBatchLookup() {
        PaidOrder refundedOrder = order(SEPTEMBER); PgPayment refundedPayment = payment(refundedOrder, 300);
        SettlementSale refunded = sale(refundedOrder, refundedPayment, "2026-09");
        PaidOrder keptOrder = order(SEPTEMBER);
        SettlementSale kept = sale(keptOrder, payment(keptOrder, 300), "2026-09");
        SettlementRefund refund = refund(refunded, confirmedIn(confirmation(refundedOrder, refundedPayment), SEPTEMBER.plusSeconds(3600)), refundedPayment, true);
        stubPage(List.of(refunded, kept));
        when(refunds.findByOrderNoIn(anyList())).thenReturn(List.of(refund));

        List<SaleResponse> content = search.execute(query).map(SaleResponse::from).getContent();

        SaleRefundResponse link = content.get(0).refund();
        assertThat(link).isEqualTo(new SaleRefundResponse(refund.getRefundId(), refund.getConfirmedAt(),
                refund.getRefundMonth(), refund.getSettlementMonth(), refund.getMonthReason()));
        assertThat(link.refundMonth()).isEqualTo("2026-09");
        assertThat(link.settlementMonth()).isEqualTo("2026-10");
        assertThat(link.monthReason()).isEqualTo("CLOSED_MONTH_ROLLOVER");
        assertThat(content.get(0).settlementMonth()).isEqualTo("2026-09");
        assertThat(content.get(1).refund()).isNull();
        verify(refunds).findByOrderNoIn(List.of(refunded.getOrderNo(), kept.getOrderNo()));
        verifyNoMoreInteractions(refunds);
    }

    @Test void administratorSalesNestTheSameRefundLinkInsideTheSaleFields() {
        PaidOrder input = order(); PgPayment payment = payment(input, 300);
        SettlementSale sale = sale(input, payment, "2026-10");
        SettlementRefund refund = refund(sale, confirmation(input, payment), payment, false);
        Page<SettlementSale> page = stubPage(List.of(sale));
        when(financials.sales(page)).thenReturn(new PageImpl<>(List.of(new SettlementSaleView(sale, payment.evidence(), null)), page.getPageable(), 1));
        when(refunds.findByOrderNoIn(anyList())).thenReturn(List.of(refund));

        AdminSaleResponse response = search.executeWithPgData(query).map(AdminSaleResponse::from).getContent().getFirst();

        assertThat(response.sale().refund().refundId()).isEqualTo(refund.getRefundId());
        assertThat(response.sale().refund().monthReason()).isEqualTo("CONFIRMED_MONTH");
        verify(refunds).findByOrderNoIn(List.of(sale.getOrderNo()));
        verifyNoMoreInteractions(refunds);
    }

    @Test void anEmptyPageDoesNotQueryTheRefundLedger() {
        stubPage(List.of());
        assertThat(search.execute(query).getContent()).isEmpty();
        verify(refunds, never()).findByOrderNoIn(anyList());
    }

    @Test void aRefundOfAnotherOrderCannotBeAttachedToASale() {
        PaidOrder input = order(); PgPayment payment = payment(input, 300);
        SettlementSale sale = sale(input, payment, "2026-10");
        PaidOrder otherOrder = order(); PgPayment otherPayment = payment(otherOrder, 300);
        SettlementSale other = sale(otherOrder, otherPayment, "2026-10");
        SettlementRefund foreign = refund(other, confirmation(otherOrder, otherPayment), otherPayment, false);
        assertThatThrownBy(() -> new SaleWithRefund(sale, foreign)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SettlementSaleView(sale, payment.evidence(), null).withRefund(foreign)).isInstanceOf(BusinessException.class);
        assertThat(new SaleWithRefund(sale, null).refund()).isNull();
    }

    private ConfirmedRefund confirmedIn(ConfirmedRefund fresh, Instant confirmedAt) {
        return new ConfirmedRefund(fresh.eventId(), confirmedAt, fresh.refundId(), fresh.attemptId(), fresh.requestEventId(), fresh.orderNo(),
                fresh.paymentId(), fresh.buyerSubject(), fresh.amount(), fresh.currency(), fresh.source(), fresh.providerTransactionId(), fresh.cancellationIds());
    }
    private Page<SettlementSale> stubPage(List<SettlementSale> content) {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SettlementSale> page = new PageImpl<>(content, pageable, content.size());
        when(sales.search(any(), any(), any())).thenReturn(page);
        return page;
    }
    private SettlementSale sale(PaidOrder order, PgPayment payment, String month) {
        return SettlementSale.record(order, new ProductOwner(1L, "game-1", "usr_seller"), payment,
                new PlatformFeePolicy("PLATFORM_MVP_V1", 1000), month, NOW);
    }
    private SettlementRefund refund(SettlementSale original, ConfirmedRefund input, PgPayment payment, boolean confirmationMonthClosed) {
        return SettlementRefund.record(input, original, proof(payment, input), new ReverseOriginalFullRefundPolicy().allocate(original),
                new UseConfirmedRefundMonthPolicy().select(new RefundMonthInput(input.refundId(), input.orderNo(), input.occurredAt(),
                        NOW, confirmationMonthClosed, false, null)));
    }
}
