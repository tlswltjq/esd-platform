package com.stove.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaymentProcessingPropertiesTest {
    @Test void invalidPollingSettingsCannotStartUnlimitedOrTightLoops() {
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,0,20,5000,5000,600000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,201,5000,5000,600000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,20,0,5000,600000)).isInstanceOf(IllegalArgumentException.class);
    }
    /** Decision 122: the held-approval lookup has its own initial delay and cap, separate from PAYMENT_RETRY_MS. */
    @Test void heldApprovalLookupSettingsAreBoundedAndOrdered() {
        PaymentProcessingProperties defaults=new PaymentProcessingProperties(true,1000,20,5000,5000,600000);
        assertThat(defaults.reviewRetryInitialMs()).isEqualTo(5000); assertThat(defaults.reviewRetryMaxMs()).isEqualTo(600000);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,20,5000,99,600000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,20,5000,300001,600000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,20,5000,5000,999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,20,5000,5000,3600001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,20,5000,60000,59999))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("review-retry-max-ms");
    }
}
