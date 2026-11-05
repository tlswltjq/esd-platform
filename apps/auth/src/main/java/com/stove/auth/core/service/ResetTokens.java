package com.stove.auth.core.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 분실 비밀번호 복구 토큰의 생성과 해시를 담당하는 작은 값 객체.
 *
 * <p>원본 토큰은 추측 불가능한 난수로 만들고, 저장소에는 원본 대신 SHA-256 해시만 남긴다.
 * 이렇게 하면 저장소가 유출돼도 복구 토큰 원본을 복원할 수 없다.
 */
final class ResetTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ResetTokens() {
    }

    /** 추측 불가능한 원본 복구 토큰을 만든다(URL 안전 Base64, 256비트). */
    static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 원본 토큰의 SHA-256 해시(64자 16진수)를 돌려준다. 저장·조회 키로 쓴다. */
    static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
