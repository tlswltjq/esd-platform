package com.stove.settlement.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.settlement.core.service.RecordConfirmedRefund;
import com.stove.settlement.core.service.RecordPaidSale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 결정 settlement-consumption-off: PG 조회가 연결되지 않은 동안 두 리스너는 기본으로 등록되지 않는다. */
class ConsumptionSwitchTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OrderEventListener.class, RefundEventListener.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(RecordPaidSale.class, () -> mock(RecordPaidSale.class))
            .withBean(RecordConfirmedRefund.class, () -> mock(RecordConfirmedRefund.class));

    @Test void listenersAreAbsentUnlessConsumptionIsEnabled() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(OrderEventListener.class).doesNotHaveBean(RefundEventListener.class));
        runner.withPropertyValues("stove.settlement.consumption-enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(OrderEventListener.class).doesNotHaveBean(RefundEventListener.class));
    }

    @Test void listenersAreRegisteredWhenConsumptionIsEnabled() {
        runner.withPropertyValues("stove.settlement.consumption-enabled=true").run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(OrderEventListener.class).hasSingleBean(RefundEventListener.class));
    }
}
