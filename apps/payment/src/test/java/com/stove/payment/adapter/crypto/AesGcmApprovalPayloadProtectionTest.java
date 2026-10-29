package com.stove.payment.adapter.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmApprovalPayloadProtectionTest {
    private final AesGcmApprovalPayloadProtection protection=new AesGcmApprovalPayloadProtection(new byte[32]);
    @Test void ciphertextDoesNotContainTheTokenAndReopensTheExactOriginalBody() {
        String body="{\"paymentToken\":\"private-token\",\"totalAmount\":9000}";
        String first=protection.seal("approval-one",body); String second=protection.seal("approval-one",body);
        assertThat(first).doesNotContain("private-token").isNotEqualTo(second);
        assertThat(protection.open("approval-one",first)).isEqualTo(body);
    }
    @Test void ciphertextCannotBeMovedToAnotherApprovalOrSilentlyModified() {
        String sealed=protection.seal("approval-one","secret-body");
        assertThatThrownBy(()->protection.open("approval-two",sealed)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret-body");
        byte[] bytes=Base64.getDecoder().decode(sealed.substring(3)); bytes[14]^=1;
        assertThatThrownBy(()->protection.open("approval-one","v1:"+Base64.getEncoder().encodeToString(bytes))).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingKeysAndInvalidCiphertextFailWithoutDumpingCredentials() {
        assertThatThrownBy(()->new AesGcmApprovalPayloadProtection(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->protection.open("id","raw-private-token")).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("raw-private-token");
    }
}
