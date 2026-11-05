package com.stove.auth.adapter.mail;

import com.stove.auth.core.port.PasswordResetMailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 복구 메일 발송을 흉내만 내는 어댑터. 실제 메일(SMTP/SES 등) 연동은 후속 범위다.
 *
 * <p>원본 토큰 자체는 비밀이므로 로그에 남기지 않는다. 발송이 일어났다는 사실과 대상 이메일만 남긴다.
 * 실제 발송 연동이 들어오면 이 클래스를 교체하고 {@link PasswordResetMailPort}는 그대로 둔다.
 */
public class LoggingPasswordResetMailAdapter implements PasswordResetMailPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetMailAdapter.class);

    @Override
    public void sendResetToken(String email, String resetToken) {
        // 원본 토큰은 기록하지 않는다. 발송 사실과 대상만 남겨 운영에서 흐름을 확인할 수 있게 한다.
        log.info("비밀번호 복구 토큰 발송(모의): email={}, tokenLength={}", email, resetToken.length());
    }
}
