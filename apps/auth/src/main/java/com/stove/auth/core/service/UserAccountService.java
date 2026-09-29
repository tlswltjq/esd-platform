package com.stove.auth.core.service;

import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.domain.RoleAuditLog;
import com.stove.auth.core.domain.RoleAuditLogRepository;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.UserRegisteredEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final RoleAuditLogRepository roleAuditLogRepository;

    public UserAccount signup(String email, String password) {
        return signup(email, password, true);
    }

    public UserAccount signupMember(String email, String password) {
        return signup(email, password, false);
    }

    private UserAccount signup(String email, String password, boolean creator) {
        if (repository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다.");
        }
        UserAccount account;
        try {
            // exists 확인과 insert 사이의 동시 가입도 DB unique 제약에서 409로 수렴시킨다.
            String hash = passwordEncoder.encode(password);
            account = repository.saveAndFlush(creator
                    ? UserAccount.creator(email, hash) : UserAccount.member(email, hash));
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다.");
        }
        outboxRecorder.record("UserAccount", account.getSubject(),
                UserRegisteredEvent.of(account.getSubject(), account.getEmail()));
        roleAuditLogRepository.save(RoleAuditLog.assigned(account, "system:signup"));
        return account;
    }
}
