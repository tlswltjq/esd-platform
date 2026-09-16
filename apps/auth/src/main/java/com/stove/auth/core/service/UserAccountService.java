package com.stove.auth.core.service;

import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.UserRegisteredEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserAccountService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxRecorder outboxRecorder;

    public UserAccount signup(String email, String password) {
        if (repository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다.");
        }
        UserAccount account = repository.save(UserAccount.creator(email, passwordEncoder.encode(password)));
        outboxRecorder.record("UserAccount", account.getSubject(),
                UserRegisteredEvent.of(account.getSubject(), account.getEmail()));
        return account;
    }
}
