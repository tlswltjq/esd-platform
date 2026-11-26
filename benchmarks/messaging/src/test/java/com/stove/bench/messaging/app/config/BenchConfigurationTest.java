package com.stove.bench.messaging.app.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BenchConfigurationTest {

    @Test
    void 기록할_계열과_실제_격리처가_같아야_뜬다() {
        assertThatCode(() -> BenchConfiguration.requireRecoveryMode("quarantine", true)).doesNotThrowAnyException();
        assertThatCode(() -> BenchConfiguration.requireRecoveryMode("dlt", false)).doesNotThrowAnyException();
    }

    @Test
    void 계열과_격리처가_어긋나거나_모르는_계열이면_기동을_멈춘다() {
        assertThatThrownBy(() -> BenchConfiguration.requireRecoveryMode("quarantine", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BenchConfiguration.requireRecoveryMode("dlt", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BenchConfiguration.requireRecoveryMode("both", true)).isInstanceOf(IllegalStateException.class);
    }
}
