package com.stove.auth.core.service;

import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.port.TokenRevocationPort;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChangePassword {
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final TokenRevocationPort tokens;

    @Transactional
    public void execute(String subject, String currentPassword, String newPassword) {
        UserAccount account = accounts.findBySubject(subject).filter(value -> value.enabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!passwords.matches(currentPassword, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "현재 비밀번호가 일치하지 않습니다.");
        }
        account.changePasswordHash(passwords.encode(newPassword));
        tokens.revokeSubject(subject);
    }
}
