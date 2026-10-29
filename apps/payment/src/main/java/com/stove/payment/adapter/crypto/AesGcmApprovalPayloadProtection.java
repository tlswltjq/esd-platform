package com.stove.payment.adapter.crypto;

import com.stove.payment.core.port.ApprovalPayloadProtection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmApprovalPayloadProtection implements ApprovalPayloadProtection {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public AesGcmApprovalPayloadProtection(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("A 256-bit approval payload key is required");
        this.key = new SecretKeySpec(key.clone(), "AES");
    }
    @Override public String seal(String approvalId, String body) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, approvalId);
            byte[] encrypted = cipher.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Approval payload encryption unavailable"); }
    }
    @Override public String open(String approvalId, String protectedBody) {
        try {
            if (protectedBody == null || !protectedBody.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] combined = Base64.getDecoder().decode(protectedBody.substring(3));
            if (combined.length < 29) throw new IllegalArgumentException();
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(combined, 0, 12), approvalId);
            return new String(cipher.doFinal(combined, 12, combined.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Approval payload decryption unavailable");
        }
    }
    private Cipher cipher(int mode, byte[] nonce, String approvalId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(approvalId.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
