package com.stove.payment.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentProcessingPropertiesTest {
    @Test void invalidPollingSettingsCannotStartUnlimitedOrTightLoops() {
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,0,20,5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,201,5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentProcessingProperties(true,1000,20,0)).isInstanceOf(IllegalArgumentException.class);
    }
}
