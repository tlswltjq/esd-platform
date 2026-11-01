package com.stove.settlement;

import static com.stove.settlement.SettlementFixtures.id;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.web.error.BusinessException;
import com.stove.settlement.core.domain.RefundAllocation;
import org.junit.jupiter.api.Test;

class RefundAllocationTest {
    @Test void missingPolicyOrOriginalSaleIdentityIsRejected() {
        assertThatThrownBy(() -> new RefundAllocation(null, id(), "ORIGINAL_V1", 9000, 900, 8100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundAllocation("REFUND_V1", "not-an-order", "ORIGINAL_V1", 9000, 900, 8100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundAllocation("REFUND_V1", id(), "", 9000, 900, 8100)).isInstanceOf(BusinessException.class);
    }

    @Test void inconsistentNegativeAndOverflowingSharesAreRejected() {
        assertThatThrownBy(() -> new RefundAllocation("REFUND_V1", id(), "ORIGINAL_V1", 9000, 900, 8099)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundAllocation("REFUND_V1", id(), "ORIGINAL_V1", 9000, -1, 9001)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundAllocation("REFUND_V1", id(), "ORIGINAL_V1", 9000, 9001, -1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new RefundAllocation("REFUND_V1", id(), "ORIGINAL_V1", Long.MAX_VALUE, 1, Long.MAX_VALUE)).isInstanceOf(BusinessException.class);
    }
}
