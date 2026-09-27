package com.stove.studio.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebhookSecretCipherServiceTest {
    @Test
    @DisplayName("webhook signing secret은 인증 암호화해 저장하고 원문으로 복호화한다")
    void roundTrip() {
        WebhookSecretCipherService cipher = new WebhookSecretCipherService("test-master-key");

        String encrypted = cipher.encrypt("whsec_super-secret");

        assertThat(encrypted).doesNotContain("super-secret");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("whsec_super-secret");
    }
}
