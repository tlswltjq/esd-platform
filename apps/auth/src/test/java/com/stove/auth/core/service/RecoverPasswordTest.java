package com.stove.auth.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stove.auth.core.domain.PasswordResetToken;
import com.stove.auth.core.domain.PasswordResetTokenRepository;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.port.PasswordResetMailPort;
import com.stove.auth.core.port.TokenRevocationPort;
import com.stove.common.web.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

class RecoverPasswordTest {

    private final UserAccountRepository accounts = Mockito.mock(UserAccountRepository.class);
    private final PasswordResetTokenRepository tokens = Mockito.mock(PasswordResetTokenRepository.class);
    private final PasswordEncoder passwords = Mockito.mock(PasswordEncoder.class);
    private final PasswordResetMailPort mail = Mockito.mock(PasswordResetMailPort.class);
    private final TokenRevocationPort revocation = Mockito.mock(TokenRevocationPort.class);
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private RecoverPassword service;

    @BeforeEach
    void setUp() {
        service = new RecoverPassword(accounts, tokens, passwords, mail, revocation, LIFETIME);
    }

    // ── 복구 요청 ────────────────────────────────────────────────

    @Test
    @DisplayName("가입된 이메일로 요청하면 토큰을 저장하고 원본을 메일로 보낸다")
    void requestForKnownEmailIssuesTokenAndSendsMail() {
        UserAccount account = UserAccount.member("owner@example.com", "{bcrypt}old");
        when(accounts.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(account));

        service.request("Owner@Example.com");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).save(saved.capture());
        ArgumentCaptor<String> sentToken = ArgumentCaptor.forClass(String.class);
        verify(mail).sendResetToken(eq("owner@example.com"), sentToken.capture());
        // 저장된 것은 해시이고, 메일로 나간 것은 원본이다. 둘은 같지 않다.
        assertThat(saved.getValue().getTokenHash()).isNotEqualTo(sentToken.getValue());
        assertThat(saved.getValue().getTokenHash()).hasSize(64);
        assertThat(saved.getValue().getSubject()).isEqualTo(account.getSubject());
        assertThat(saved.getValue().usable(Instant.now())).isTrue();
    }

    @Test
    @DisplayName("가입되지 않은 이메일로 요청해도 조용히 성공하며 아무것도 저장·발송하지 않는다")
    void requestForUnknownEmailSilentlySucceeds() {
        when(accounts.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        service.request("ghost@example.com");

        verify(tokens, never()).save(any());
        verifyNoInteractions(mail);
    }

    // ── 복구 실행 ────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 토큰과 새 비밀번호로 재설정하면 해시를 바꾸고 토큰을 소비하며 전 토큰을 폐기한다")
    void resetWithValidTokenChangesPasswordAndRevokes() {
        UserAccount account = UserAccount.member("owner@example.com", "{bcrypt}old");
        String raw = "raw-reset-token-value";
        String hash = ResetTokens.hash(raw);
        PasswordResetToken token = PasswordResetToken.issue(hash, account.getSubject(), Instant.now(), 1800);
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(token));
        when(accounts.findBySubject(account.getSubject())).thenReturn(Optional.of(account));
        when(passwords.encode("brand-new-password")).thenReturn("{bcrypt}new");

        service.reset(raw, "brand-new-password");

        assertThat(account.getPasswordHash()).isEqualTo("{bcrypt}new");
        assertThat(token.isConsumed()).isTrue();
        verify(revocation).revokeSubject(account.getSubject());
    }

    @Test
    @DisplayName("이미 사용된 토큰은 거절하고 비밀번호를 바꾸지 않는다")
    void resetWithConsumedTokenIsRejected() {
        UserAccount account = UserAccount.member("owner@example.com", "{bcrypt}old");
        String raw = "used-token";
        String hash = ResetTokens.hash(raw);
        PasswordResetToken token = PasswordResetToken.issue(hash, account.getSubject(), Instant.now(), 1800);
        token.consume();
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.reset(raw, "brand-new-password"))
                .isInstanceOf(BusinessException.class);
        verify(passwords, never()).encode(anyString());
        verify(revocation, never()).revokeSubject(anyString());
    }

    @Test
    @DisplayName("만료된 토큰은 거절한다")
    void resetWithExpiredTokenIsRejected() {
        UserAccount account = UserAccount.member("owner@example.com", "{bcrypt}old");
        String raw = "stale-token";
        String hash = ResetTokens.hash(raw);
        // 발급은 과거, 유효시간 1초 → 지금은 만료.
        PasswordResetToken token = PasswordResetToken.issue(hash, account.getSubject(),
                Instant.now().minusSeconds(3600), 1);
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.reset(raw, "brand-new-password"))
                .isInstanceOf(BusinessException.class);
        verify(revocation, never()).revokeSubject(anyString());
    }

    @Test
    @DisplayName("존재하지 않는 토큰은 거절한다")
    void resetWithUnknownTokenIsRejected() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset("no-such-token", "brand-new-password"))
                .isInstanceOf(BusinessException.class);
        verify(passwords, never()).encode(anyString());
    }
}
