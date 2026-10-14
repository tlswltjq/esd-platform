package com.stove.auth.config;

import com.stove.auth.core.service.BootstrapAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 운영자 계정은 공개 가입으로 만들지 않고 배포 시 secret 환경변수로 최초 bootstrap한다. */
@Component
public class BootstrapAccountsRunner implements ApplicationRunner {

    private final BootstrapAccounts bootstrapAccounts;
    private final String reviewerEmail;
    private final String reviewerPassword;
    private final String adminEmail;
    private final String adminPassword;

    public BootstrapAccountsRunner(
            BootstrapAccounts bootstrapAccounts,
            @Value("${stove.auth.bootstrap.reviewer-email:}") String reviewerEmail,
            @Value("${stove.auth.bootstrap.reviewer-password:}") String reviewerPassword,
            @Value("${stove.auth.bootstrap.admin-email:}") String adminEmail,
            @Value("${stove.auth.bootstrap.admin-password:}") String adminPassword) {
        this.bootstrapAccounts = bootstrapAccounts;
        this.reviewerEmail = reviewerEmail;
        this.reviewerPassword = reviewerPassword;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapAccounts.execute(reviewerEmail, reviewerPassword, adminEmail, adminPassword);
    }
}
