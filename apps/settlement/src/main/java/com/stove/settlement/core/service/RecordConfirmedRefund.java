package com.stove.settlement.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.ConfirmedRefund;
import com.stove.settlement.core.domain.FullRefundAllocationPolicy;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgRefund;
import com.stove.settlement.core.domain.PgTransactionRecord;
import com.stove.settlement.core.domain.PgTransactionRepository;
import com.stove.settlement.core.domain.RefundMonthInput;
import com.stove.settlement.core.domain.RefundMonthSelection;
import com.stove.settlement.core.domain.RefundSettlementMonthPolicy;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementMonth;
import com.stove.settlement.core.domain.SettlementRefund;
import com.stove.settlement.core.domain.SettlementRefundRepository;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.PgRefunds;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class RecordConfirmedRefund {
    public static final String CONSUMER_GROUP = "settlement-confirmed-refund";
    private final SettlementRefundRepository refunds;
    private final SettlementSaleRepository sales;
    private final SellerMonthlySettlementRepository months;
    private final PgTransactionRepository transactions;
    private final PgRefunds supplier;
    private final ProcessedEventGuard inbox;
    private final FullRefundAllocationPolicy allocation;
    private final RefundSettlementMonthPolicy monthPolicy;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public RecordConfirmedRefund(SettlementRefundRepository refunds, SettlementSaleRepository sales,
            SellerMonthlySettlementRepository months, PgTransactionRepository transactions, PgRefunds supplier,
            ProcessedEventGuard inbox, FullRefundAllocationPolicy allocation, RefundSettlementMonthPolicy monthPolicy,
            Clock clock, PlatformTransactionManager manager) {
        this.refunds=refunds; this.sales=sales; this.months=months; this.transactions=transactions; this.supplier=supplier;
        this.inbox=inbox; this.allocation=allocation; this.monthPolicy=monthPolicy; this.clock=clock;
        transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public SettlementRefund execute(ConfirmedRefund input) {
        if (input == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PG 취소 조회 중 업무 트랜잭션을 유지할 수 없습니다.");
        SettlementRefund duplicate = transaction.execute(tx -> existing(input));
        if (duplicate != null) return duplicate;
        SettlementSale original = sales.findByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "환불에 대응하는 원매출이 아직 없습니다."));
        input.requireSale(original);
        PgTransactionRecord fact = transactions.findByPaymentId(input.paymentId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        fact.requireSale(original); PgPayment payment = fact.payment(null);
        PgRefund proof = supplier.require(payment, input); proof.requireOriginal(payment); proof.requireConfirmation(input);
        for (int attempt=0; attempt<5; attempt++) {
            try { return transaction.execute(tx -> record(input, proof)); }
            catch (DataIntegrityViolationException | TransientDataAccessException collision) {
                if (attempt==4) throw new BusinessException(ErrorCode.CONFLICT, "환불 원장 업무 키가 충돌했습니다.");
            }
        }
        throw new IllegalStateException("Unreachable refund retry state");
    }
    private SettlementRefund existing(ConfirmedRefund input) {
        SettlementRefund previous = refunds.findByRefundId(input.refundId()).orElseGet(() -> refunds.findByOrderNo(input.orderNo()).orElse(null));
        if (previous != null) {
            previous.requireSame(input);
            if (inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.REFUND_PAYMENT_CONFIRMED))
                throw new BusinessException(ErrorCode.CONFLICT, "환불 원장과 Inbox가 다릅니다.");
        }
        return previous;
    }
    private SettlementRefund record(ConfirmedRefund input, PgRefund proof) {
        SettlementSale original = sales.lockByOrderNo(input.orderNo()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        input.requireSale(original);
        SettlementRefund duplicate = existing(input); if (duplicate != null) return duplicate;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String confirmedMonth = SettlementMonth.at(input.occurredAt()).value();
        SellerMonthlySettlement confirmed = lockMonth(original.getSellerSubject(), confirmedMonth, now);
        SellerMonthlySettlement selected = confirmed;
        if (confirmed.isClosed()) {
            for (int rollover=0; rollover<3; rollover++) {
                now=clock.instant().truncatedTo(ChronoUnit.MICROS);
                selected=lockMonth(original.getSellerSubject(), SettlementMonth.at(now).value(), now);
                if (!selected.isClosed() && selected.getSettlementMonth().equals(SettlementMonth.at(clock.instant()).value())) break;
            }
            if (selected.isClosed() || !selected.getSettlementMonth().equals(SettlementMonth.at(clock.instant()).value()))
                throw new BusinessException(ErrorCode.CONFLICT, "환불을 반영할 현재 OPEN 월이 없습니다.");
        }
        now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        RefundMonthSelection selection=monthPolicy.select(new RefundMonthInput(input.refundId(), input.orderNo(), input.occurredAt(), now,
                confirmed.isClosed(), confirmed.isClosed() ? selected.isClosed() : false, null));
        if (!selection.settlementMonth().equals(selected.getSettlementMonth())) throw new BusinessException(ErrorCode.CONFLICT, "월 경계가 변경됐습니다.");
        SettlementRefund refund=SettlementRefund.record(input, original, proof, allocation.allocate(original), selection);
        if (!inbox.firstDelivery(input.eventId(), CONSUMER_GROUP, EventType.REFUND_PAYMENT_CONFIRMED))
            throw new BusinessException(ErrorCode.CONFLICT, "환불 원장과 Inbox가 다릅니다.");
        selected.addRefund(refund.totals(), now);
        return refunds.saveAndFlush(refund);
    }
    private SellerMonthlySettlement lockMonth(String seller, String month, Instant now) {
        return months.lock(seller, month).orElseGet(() -> months.saveAndFlush(SellerMonthlySettlement.open(seller, month, now)));
    }
}
