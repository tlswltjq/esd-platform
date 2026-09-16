package com.stove.auth.config;

import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 운영자 계정은 공개 가입으로 만들지 않고 배포 시 secret 환경변수로 최초 bootstrap한다. */
@Component
public class BootstrapAccounts implements ApplicationRunner {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String reviewerEmail;
    private final String reviewerPassword;
    private final String adminEmail;
    private final String adminPassword;

    public BootstrapAccounts(
            UserAccountRepository repository,
            PasswordEncoder passwordEncoder,
            @Value("${stove.auth.bootstrap.reviewer-email:}") String reviewerEmail,
            @Value("${stove.auth.bootstrap.reviewer-password:}") String reviewerPassword,
            @Value("${stove.auth.bootstrap.admin-email:}") String adminEmail,
            @Value("${stove.auth.bootstrap.admin-password:}") String adminPassword) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.reviewerEmail = reviewerEmail;
        this.reviewerPassword = reviewerPassword;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createIfConfigured(reviewerEmail, reviewerPassword, Set.of(PlatformRole.REVIEWER));
        createIfConfigured(adminEmail, adminPassword, Set.of(PlatformRole.ADMIN, PlatformRole.REVIEWER));
    }

    private void createIfConfigured(String email, String password, Set<PlatformRole> roles) {
        if (email == null || email.isBlank() || password == null || password.isBlank()
                || repository.existsByEmailIgnoreCase(email)) {
            return;
        }
        repository.save(UserAccount.withRoles(email, passwordEncoder.encode(password), roles));
    }
}
