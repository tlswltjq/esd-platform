package com.stove.settlement;

import com.stove.settlement.core.domain.PaidOrder;
import com.stove.settlement.core.domain.PgCost;
import com.stove.settlement.core.domain.PgPayment;
import com.stove.settlement.core.domain.PgSettlement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SettlementFixtures {
    public static final Instant NOW=Instant.parse("2026-10-18T03:00:00Z");
    private SettlementFixtures() { }
    public static String id() { return UUID.randomUUID().toString(); }
    public static PaidOrder order() { return order(NOW.minusSeconds(10)); }
    public static PaidOrder order(Instant occurredAt) {
        return new PaidOrder(id(),occurredAt,id(),id(),id(),id(),"usr_buyer",1L,"game-1","Frozen title",1,10000,"KRW");
    }
    public static PgPayment payment(PaidOrder order,long fee) {
        return new PgPayment(order.paymentId(),order.orderNo(),order.amount(),order.currency(),
                new PgCost("SIMULATED",id(),order.occurredAt().minusSeconds(1),fee==300?"SIMULATED_FEE_V1":"SIMULATED_FEE_TEST_"+fee,Math.toIntExact(fee),fee));
    }
    public static PgSettlement report(List<PgPayment> items) {
        long paid=0;long fee=0;
        for(PgPayment item:items) { paid=Math.addExact(paid,item.amount());fee=Math.addExact(fee,item.cost().getPgFeeAmount()); }
        return new PgSettlement("SIMULATED",id(),"KRW",NOW,NOW,"SIMULATED_PAID",paid,fee,paid-fee,items.size(),items);
    }
}
