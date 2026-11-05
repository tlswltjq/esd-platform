package com.stove.auth.core.service;

import com.stove.auth.core.domain.PasswordResetToken;
import com.stove.auth.core.domain.PasswordResetTokenRepository;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.port.PasswordResetMailPort;
import com.stove.auth.core.port.TokenRevocationPort;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분실 비밀번호 복구. 현재 비밀번호를 모르는 사용자가 이메일로 본인을 증명하고 새 비밀번호를 정한다.
 *
 * <p>요청 단계는 가입 여부를 외부에 드러내지 않는다. 가입된 이메일이면 일회용 토큰을 발급해 메일로
 * 보내고, 가입되지 않은 이메일이어도 똑같이 조용히 성공한다. 실행 단계는 토큰이 존재·미소비·미만료일
 * 때만 새 비밀번호를 적용하고, 토큰을 소비 처리한 뒤 해당 계정의 모든 Refresh Token 계열을 폐기한다.
 *
 * <p>유효시간과 의존성 조립은 {@code config}가 담당한다(이 클래스는 스프링 스테레오타입을 달지 않는다).
 */
public class RecoverPassword {

    private final UserAccountRepository accounts;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final PasswordResetMailPort mail;
    private final TokenRevocationPort revocation;
    private final long lifetimeSeconds;

    public RecoverPassword(UserAccountRepository accounts, PasswordResetTokenRepository tokens,
            PasswordEncoder passwords, PasswordResetMailPort mail, TokenRevocationPort revocation,
            Duration lifetime) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.passwords = passwords;
        this.mail = mail;
        this.revocation = revocation;
        this.lifetimeSeconds = lifetime.toSeconds();
    }

    /**
     * 복구를 요청한다. 가입 여부와 무관하게 동일하게 성공한다(계정 존재 여부를 숨긴다).
     * 가입된 이메일일 때만 토큰을 발급·저장하고 원본을 메일로 보낸다.
     */
    @Transactional
    public void request(String email) {
        accounts.findByEmailIgnoreCase(email.toLowerCase(Locale.ROOT)).ifPresent(account -> {
            String rawToken = ResetTokens.newRawToken();
            tokens.save(PasswordResetToken.issue(ResetTokens.hash(rawToken), account.getSubject(),
                    Instant.now(), lifetimeSeconds));
            mail.sendResetToken(account.getEmail(), rawToken);
        });
    }

    /**
     * 복구를 실행한다. 원본 토큰이 유효할 때만 새 비밀번호를 적용한다.
     *
     * @param rawToken    사용자가 제시한 원본 복구 토큰.
     * @param newPassword 새 비밀번호(12~100자는 컨트롤러에서 검증).
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        PasswordResetToken token = tokens.findByTokenHash(ResetTokens.hash(rawToken))
                .filter(value -> value.usable(Instant.now()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "복구 링크가 유효하지 않거나 만료되었습니다."));
        UserAccount account = accounts.findBySubject(token.getSubject())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "복구 링크가 유효하지 않거나 만료되었습니다."));
        account.changePasswordHash(passwords.encode(newPassword));
        token.consume();
        revocation.revokeSubject(account.getSubject());
    }
}
