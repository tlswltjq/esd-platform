package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import com.stove.settlement.core.domain.RefundMonthInput;
import com.stove.settlement.core.domain.RefundMonthSelection;
import com.stove.settlement.core.domain.RefundSettlementMonthPolicy;
import com.stove.settlement.core.domain.UseConfirmedRefundMonthPolicy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundSettlementMonthPolicyTest {
    private static final Instant CONFIRMED = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-11-02T00:00:00Z");
    private final RefundSettlementMonthPolicy policy = new UseConfirmedRefundMonthPolicy();

    @Test void anOpenConfirmationMonthIsUsedEvenWhenDeliveryIsLate() {
        RefundMonthSelection selected = policy.select(input(false, false, null));
        assertThat(selected.refundMonth()).isEqualTo("2026-10");
        assertThat(selected.settlementMonth()).isEqualTo("2026-10");
        assertThat(selected.reason()).isEqualTo("CONFIRMED_MONTH");
        assertThat(selected.policyId()).isEqualTo("REFUND_CONFIRMED_MONTH_V1");
        assertThat(selected.confirmedAt()).isEqualTo(CONFIRMED);
        assertThat(selected.firstReceivedAt()).isEqualTo(RECEIVED);
    }

    @Test void aClosedConfirmationMonthRollsIntoTheCurrentOpenMonth() {
        RefundMonthSelection selected = policy.select(input(true, false, null));
        assertThat(selected.refundMonth()).isEqualTo("2026-10");
        assertThat(selected.settlementMonth()).isEqualTo("2026-11");
        assertThat(selected.reason()).isEqualTo("CLOSED_MONTH_ROLLOVER");
    }

    @Test void theKoreanMonthBoundaryUsesTheFirstFinancialConfirmation() {
        Instant justBefore = Instant.parse("2026-10-31T14:59:59.999999Z");
        Instant atBoundary = Instant.parse("2026-10-31T15:00:00Z");
        RefundMonthSelection before = policy.select(new RefundMonthInput(id(), id(), justBefore, RECEIVED, false, false, null));
        RefundMonthSelection after = policy.select(new RefundMonthInput(id(), id(), atBoundary, RECEIVED, false, false, null));
        assertThat(before.settlementMonth()).isEqualTo("2026-10");
        assertThat(after.settlementMonth()).isEqualTo("2026-11");
    }

    @Test void aClosedCurrentMonthCannotBeUsedAsAFallback() {
        assertThatThrownBy(() -> policy.select(input(true, true, null))).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> policy.select(new RefundMonthInput(id(), id(), RECEIVED.minusSeconds(1), RECEIVED, true, true, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void aRecordedSelectionSurvivesLaterClosingReplayAndPolicyVersionChanges() {
        String refundId = id(); String orderNo = id();
        RefundMonthSelection recorded = new RefundMonthSelection("EARLIER_POLICY_V0", refundId, orderNo, CONFIRMED,
                CONFIRMED.plusSeconds(1), "2026-10", "2026-10", "CONFIRMED_MONTH");
        RefundMonthSelection replay = policy.select(new RefundMonthInput(refundId, orderNo, CONFIRMED, RECEIVED, true, true, recorded));
        assertThat(replay).isSameAs(recorded);
        assertThat(replay.firstReceivedAt()).isEqualTo(CONFIRMED.plusSeconds(1));
        assertThat(replay.policyId()).isEqualTo("EARLIER_POLICY_V0");
    }

    @Test void anotherRefundOrderOrConfirmationCannotReuseTheRecordedMonth() {
        RefundMonthSelection recorded = policy.select(input(false, false, null));
        assertThatThrownBy(() -> new RefundMonthInput(id(), recorded.orderNo(), CONFIRMED, RECEIVED, true, false, recorded))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundMonthInput(recorded.refundId(), id(), CONFIRMED, RECEIVED, true, false, recorded))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundMonthInput(recorded.refundId(), recorded.orderNo(), CONFIRMED.plusSeconds(1), RECEIVED, true, false, recorded))
                .isInstanceOf(BusinessException.class);
    }

    @Test void invalidIdentityChronologyAndContradictorySameMonthStatesAreRejected() {
        assertThatThrownBy(() -> new RefundMonthInput("bad-refund", id(), CONFIRMED, RECEIVED, false, false, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundMonthInput(id(), id(), null, RECEIVED, false, false, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundMonthInput(id(), id(), CONFIRMED, CONFIRMED.minusSeconds(1), false, false, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundMonthInput(id(), id(), CONFIRMED.plusNanos(1), RECEIVED, false, false, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundMonthInput(id(), id(), RECEIVED.minusSeconds(1), RECEIVED, true, false, null)).isInstanceOf(BusinessException.class);
    }

    @Test void selectionEvidenceCannotMisstateItsConfirmationMonthOrUseAFutureMonth() {
        assertThatThrownBy(() -> new RefundMonthSelection("POLICY", id(), id(), CONFIRMED, RECEIVED,
                "2026-09", "2026-10", "CONFIRMED_MONTH")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundMonthSelection("POLICY", id(), id(), CONFIRMED, RECEIVED,
                "2026-10", "2026-12", "CLOSED_MONTH_ROLLOVER")).isInstanceOf(BusinessException.class);
    }

    @Test void aValidatedMonthSelectionInputIsRequired() {
        assertThatThrownBy(() -> policy.select(null)).isInstanceOf(BusinessException.class);
    }

    private RefundMonthInput input(boolean confirmationClosed, boolean currentClosed, RefundMonthSelection recorded) {
        return new RefundMonthInput(id(), id(), CONFIRMED, RECEIVED, confirmationClosed, currentClosed, recorded);
    }
}
