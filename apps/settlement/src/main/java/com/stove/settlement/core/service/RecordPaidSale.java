package com.stove.settlement.core.service;

import com.stove.common.kafka.contract.EventType;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PlatformFeePolicy;
import com.stove.settlement.core.domain.ProductOwner;
import com.stove.settlement.core.domain.SellerMonthlySettlement;
import com.stove.settlement.core.domain.SellerMonthlySettlementRepository;
import com.stove.settlement.core.domain.SettlementMonth;
import com.stove.settlement.core.domain.SettlementSale;
import com.stove.settlement.core.domain.SettlementSaleRepository;
import com.stove.settlement.core.port.CatalogOwners;
import com.stove.settlement.core.port.PgPayments;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class RecordPaidSale {
    public static final String CONSUMER_GROUP="settlement-paid-order";
    private final SettlementSaleRepository sales;
    private final SellerMonthlySettlementRepository months;
    private final ProcessedEventGuard guard;
    private final PgPayments payments;
    private final CatalogOwners owners;
    private final PlatformFeePolicy policy;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public RecordPaidSale(SettlementSaleRepository sales,SellerMonthlySettlementRepository months,ProcessedEventGuard guard,
            PgPayments payments,CatalogOwners owners,PlatformFeePolicy policy,Clock clock,PlatformTransactionManager transactions) {
        this.sales=sales; this.months=months; this.guard=guard; this.payments=payments; this.owners=owners; this.policy=policy; this.clock=clock;
        transaction=new TransactionTemplate(transactions); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public SettlementSale execute(PaidOrder input) {
        if(input==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("외부 조회 중 업무 트랜잭션을 유지할 수 없습니다.");
        SettlementSale duplicate=transaction.execute(tx -> existing(input));
        if(duplicate!=null) return duplicate;
        PgPayment payment=payments.require(input.paymentId()); payment.requireOrder(input);
        ProductOwner owner=owners.require(input.productCode()); owner.requireOrder(input);
        for(int attempt=0;attempt<5;attempt++) {
            try { return transaction.execute(tx -> record(input,payment,owner)); }
            catch(DataIntegrityViolationException | TransientDataAccessException collision) {
                if(attempt==4) throw new BusinessException(ErrorCode.CONFLICT,"매출 업무 키가 충돌했습니다.");
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }
    private SettlementSale existing(PaidOrder input) {
        SettlementSale previous=sales.findByOrderNo(input.orderNo()).orElse(null);
        if(previous!=null) {
            previous.requireSame(input);
            if(guard.firstDelivery(input.eventId(),CONSUMER_GROUP,EventType.ORDER_PAID))
                throw new BusinessException(ErrorCode.CONFLICT,"Inbox와 매출이 일치하지 않습니다.");
        }
        return previous;
    }
    private SettlementSale record(PaidOrder input,PgPayment payment,ProductOwner owner) {
        SettlementSale previous=existing(input);
        if(previous!=null) return previous;
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        String original=SettlementMonth.at(input.occurredAt()).value();
        SellerMonthlySettlement month=lockMonth(owner.sellerSubject(),original,now);
        if(month.isClosed()) {
            for(int rollover=0;rollover<3;rollover++) {
                now=clock.instant().truncatedTo(ChronoUnit.MICROS);
                month=lockMonth(owner.sellerSubject(),SettlementMonth.at(now).value(),now);
                if(!month.isClosed() && month.getSettlementMonth().equals(SettlementMonth.at(clock.instant()).value())) break;
            }
            if(month.isClosed() || !month.getSettlementMonth().equals(SettlementMonth.at(clock.instant()).value()))
                throw new BusinessException(ErrorCode.CONFLICT,"현재 반영할 OPEN 월을 확인할 수 없습니다.");
        }
        previous=existing(input);
        if(previous!=null) return previous;
        now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        SettlementSale sale=SettlementSale.record(input,owner,payment,policy,month.getSettlementMonth(),now);
        if(!guard.firstDelivery(input.eventId(),CONSUMER_GROUP,EventType.ORDER_PAID))
            throw new BusinessException(ErrorCode.CONFLICT,"Inbox와 매출이 일치하지 않습니다.");
        month.add(sale.totals(),now);
        return sales.saveAndFlush(sale);
    }
    private SellerMonthlySettlement lockMonth(String seller,String month,Instant now) {
        return months.lock(seller,month).orElseGet(() -> months.saveAndFlush(SellerMonthlySettlement.open(seller,month,now)));
    }
}
