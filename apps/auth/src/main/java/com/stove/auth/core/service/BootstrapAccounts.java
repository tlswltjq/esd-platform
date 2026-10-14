package com.stove.auth.core.service;

import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.domain.RoleAuditLog;
import com.stove.auth.core.domain.RoleAuditLogRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BootstrapAccounts {

    private final UserAccountRepository repository;
    private final RoleAuditLogRepository roleAuditLogRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void execute(String reviewerEmail, String reviewerPassword, String adminEmail, String adminPassword) {
        createIfConfigured(reviewerEmail, reviewerPassword, Set.of(PlatformRole.REVIEWER));
        createIfConfigured(adminEmail, adminPassword, Set.of(PlatformRole.ADMIN, PlatformRole.REVIEWER));
    }

    private void createIfConfigured(String email, String password, Set<PlatformRole> roles) {
        if (email == null || email.isBlank() || password == null || password.isBlank()
                || repository.existsByEmailIgnoreCase(email)) {
            return;
        }
        UserAccount account = repository.save(UserAccount.withRoles(email, passwordEncoder.encode(password), roles));
        roleAuditLogRepository.save(RoleAuditLog.assigned(account, "system:bootstrap"));
    }
}
