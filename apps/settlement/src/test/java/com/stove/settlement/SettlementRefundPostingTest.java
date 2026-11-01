package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.NOW;
import static com.stove.settlement.SettlementFixtures.id;
import static com.stove.settlement.SettlementFixtures.order;
import static com.stove.settlement.SettlementFixtures.payment;
import static com.stove.settlement.SettlementRefundFixtures.confirmation;
import static com.stove.settlement.SettlementRefundFixtures.proof;
import static com.stove.settlement.SettlementRefundFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.kafka.EventEnvelope;
import com.stove.common.kafka.contract.EventType;
import com.stove.common.kafka.contract.payload.RefundPaymentConfirmedEvent;
import com.stove.settlement.api.listener.SettlementRefundMapper;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.domain.PgRefundCancellation;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.RefundMonthInput;
import com.stove.settlement.core.domain.ReverseOriginalFullRefundPolicy;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementTotals;
import com.stove.settlement.core.domain.UseConfirmedRefundMonthPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementRefundPostingTest {
    @Test void mapperRejectsBrokenEnvelopeAndPreservesOnlyValidatedFinancialInput() {
        PaidOrder input=order();ConfirmedRefund confirmed=confirmation(input,payment(input,300));RefundPaymentConfirmedEvent event=event(confirmed);
        assertThat(SettlementRefundMapper.from(new EventEnvelope(event.eventId(),event.eventType(),event.orderNo(),"{}"),event)).isEqualTo(confirmed);
        assertThatThrownBy(() -> SettlementRefundMapper.from(new EventEnvelope(id(),event.eventType(),event.orderNo(),"{}"),event)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SettlementRefundMapper.from(new EventEnvelope(event.eventId(),EventType.REFUND_EXECUTION_SUCCEEDED,event.orderNo(),"{}"),event)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SettlementRefundMapper.from(new EventEnvelope(event.eventId(),event.eventType(),id(),"{}"),event)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ConfirmedRefund(confirmed.eventId(),confirmed.occurredAt(),confirmed.refundId(),confirmed.attemptId(),confirmed.requestEventId(),
                confirmed.orderNo(),confirmed.paymentId(),confirmed.buyerSubject(),confirmed.amount(),confirmed.currency(),"REQUESTED",confirmed.providerTransactionId(),confirmed.cancellationIds()))
                .isInstanceOf(BusinessException.class);
    }
    @Test void multipleSuccessfulCancellationsAreMatchedAsOneFullRefund() {
        PaidOrder input=order();PgPayment payment=payment(input,300);ConfirmedRefund template=confirmation(input,payment);
        String first=id();String second=id();
        ConfirmedRefund confirmed=new ConfirmedRefund(template.eventId(),template.occurredAt(),template.refundId(),template.attemptId(),template.requestEventId(),
                template.orderNo(),template.paymentId(),template.buyerSubject(),template.amount(),template.currency(),template.source(),template.providerTransactionId(),List.of(second,first));
        PgRefund proof=new PgRefund(payment,List.of(new PgRefundCancellation("SIMULATED",second,6000,null),new PgRefundCancellation("SIMULATED",first,4000,NOW.minusSeconds(3))),NOW.minusSeconds(3));
        proof.requireConfirmation(confirmed);
        assertThat(proof.cancellations()).hasSize(2);
    }
    @Test void refundLedgerPreservesTheOriginalSaleAndOffsetsItsMonthlyNet() {
        PaidOrder input = order(); PgPayment payment = payment(input, 300);
        SettlementSale original = sale(input, payment); ConfirmedRefund confirmed = confirmation(input, payment);
        SettlementRefund refund = refund(original, confirmed, proof(payment, confirmed));
        SellerMonthlySettlement month = SellerMonthlySettlement.open("usr_seller", "2026-10", NOW);
        month.add(original.totals(), NOW); month.addRefund(refund.totals(), NOW);
        assertThat(original.getSellerPayoutAmount()).isEqualTo(9000);
        assertThat(month.getPaidAmount()).isEqualTo(10000);
        assertThat(month.getRefundAmount()).isEqualTo(10000);
        assertThat(month.getNetPaidAmount()).isZero();
        assertThat(month.getNetPlatformFeeAmount()).isZero();
        assertThat(month.getNetSellerPayoutAmount()).isZero();
        assertThat(refund.confirmation()).isEqualTo(confirmed);
        assertThat(refund.selection().settlementMonth()).isEqualTo("2026-10");
    }

    @Test void aRefundOnlyMonthKeepsNegativeNetWithoutInventingCollectionOrCarryover() {
        PaidOrder input = order(); PgPayment payment = payment(input, 300); ConfirmedRefund confirmed = confirmation(input, payment);
        SettlementRefund refund = refund(sale(input, payment), confirmed, proof(payment, confirmed));
        SellerMonthlySettlement month = SellerMonthlySettlement.open("usr_seller", "2026-10", NOW);
        month.addRefund(refund.totals(), NOW);
        assertThat(month.getNetSellerPayoutAmount()).isEqualTo(-9000);
        month.close(SettlementTotals.empty(), refund.totals(), NOW);
        assertThat(month.isClosed()).isTrue();
        assertThatThrownBy(() -> month.addRefund(refund.totals(), NOW)).isInstanceOf(BusinessException.class);
    }

    @Test void closeChecksRefundTotalsAsWellAsGrossSales() {
        PaidOrder input = order(); PgPayment payment = payment(input, 300); ConfirmedRefund confirmed = confirmation(input, payment);
        SettlementRefund refund = refund(sale(input, payment), confirmed, proof(payment, confirmed));
        SellerMonthlySettlement month = SellerMonthlySettlement.open("usr_seller", "2026-10", NOW);
        month.addRefund(refund.totals(), NOW);
        assertThatThrownBy(() -> month.close(SettlementTotals.empty(), SettlementTotals.empty(), NOW)).isInstanceOf(BusinessException.class);
        assertThat(month.isClosed()).isFalse();
    }

    @Test void cancellationEvidenceMustCoverTheEntireOriginalPaymentExactlyOnce() {
        PaidOrder input = order(); PgPayment payment = payment(input, 300);
        PgRefundCancellation shortPart = new PgRefundCancellation("SIMULATED", id(), 9999, NOW);
        assertThatThrownBy(() -> new PgRefund(payment, List.of(shortPart), NOW)).isInstanceOf(BusinessException.class);
        PgRefundCancellation part = new PgRefundCancellation("SIMULATED", id(), 5000, NOW);
        assertThatThrownBy(() -> new PgRefund(payment, List.of(part, part), NOW)).isInstanceOf(BusinessException.class);
        ConfirmedRefund confirmed = confirmation(input, payment);
        PgRefund different = new PgRefund(payment, List.of(new PgRefundCancellation("SIMULATED", id(), 10000, NOW)), NOW);
        assertThatThrownBy(() -> different.requireConfirmation(confirmed)).isInstanceOf(BusinessException.class);
    }

    private SettlementSale sale(PaidOrder order, PgPayment payment) {
        return SettlementSale.record(order, new ProductOwner(1L, "game-1", "usr_seller"), payment,
                new PlatformFeePolicy("PLATFORM_MVP_V1", 1000), "2026-10", NOW);
    }
    private SettlementRefund refund(SettlementSale original, ConfirmedRefund input, PgRefund proof) {
        return SettlementRefund.record(input, original, proof, new ReverseOriginalFullRefundPolicy().allocate(original),
                new UseConfirmedRefundMonthPolicy().select(new RefundMonthInput(input.refundId(), input.orderNo(), input.occurredAt(),
                        NOW, false, false, null)));
    }
}
