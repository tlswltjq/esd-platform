package com.stove.auth.core.port;

/**
 * 분실 비밀번호 복구 안내를 사용자에게 전달하는 외부 연동 계약.
 *
 * <p>실제 메일 발송(SMTP/SES 등)은 후속 범위다. 현재 구현은 발송을 흉내만 내며, 복구의 안전 핵심
 * (토큰 발급·만료·일회성·검증 후 전 토큰 폐기)은 서비스에서 완성한다. 실제 발송 연동은 이 포트의
 * adapter 구현만 교체해 끼운다.
 */
public interface PasswordResetMailPort {

    /**
     * 복구 링크에 필요한 원본 토큰을 해당 이메일로 보낸다.
     *
     * @param email      복구를 요청한 계정의 이메일 주소.
     * @param resetToken 사용자가 복구에 제시할 원본 토큰(해시 이전 값).
     */
    void sendResetToken(String email, String resetToken);
}
