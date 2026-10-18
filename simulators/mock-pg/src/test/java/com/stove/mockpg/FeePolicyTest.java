package com.stove.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.mockpg.config.MockPgProperties;
import com.stove.mockpg.core.domain.Charge;
import org.junit.jupiter.api.Test;

class FeePolicyTest {
    @Test void roundsEachTransactionAndHandlesLongBoundaryExactly() {
        assertThat(Charge.fee(10000,300)).isEqualTo(300);
        assertThat(Charge.fee(50,100)).isEqualTo(1);
        assertThat(Charge.fee(Long.MAX_VALUE,10000)).isEqualTo(Long.MAX_VALUE);
        assertThat(Charge.fee(1,0)).isZero();
        assertThatThrownBy(() -> Charge.fee(100,-1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void keysAndPolicyAreValidatedBeforeServingRequests() {
        assertThatThrownBy(() -> new MockPgProperties("p","p","c",Charge.Decision.APPROVED,"v1",300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MockPgProperties("p","s","",Charge.Decision.APPROVED,"v1",300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MockPgProperties("p","s","c",Charge.Decision.APPROVED,"v1",10001)).isInstanceOf(IllegalArgumentException.class);
    }
}
