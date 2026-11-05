package com.stove.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.auth.core.domain.AccountStatus;
import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.service.ManageAccount;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.web.error.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 운영자의 계정 상태·역할 관리를 실제 MySQL에서 검증한다.
 *
 * <p>상태 전환·역할 회수가 DB에 반영되고, 변경마다 {@code role_audit_log}에 감사가 남으며, 대상
 * 계정의 Refresh Token 계열이 즉시 폐기되는지를 끝까지 본다. ADMIN 권한 판정은 게이트웨이 몫이므로
 * 여기서는 서비스를 직접 호출해 운영 안전 규칙과 영속 효과를 확인한다.
 */
@SpringBootTest(properties = {
        "stove.outbox.relay-enabled=false", "stove.auth.bff-client-secret=integration-secret",
        "stove.auth.bff-redirect-uri=http://localhost:3000/login/oauth2/code/gateway-bff"})
@Import(InfraContainers.MySql.class)
class AccountManagementIntegrationTest {

    @Autowired ManageAccount manageAccount;
    @Autowired UserAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    private UserAccount persistAdmin() {
        return accounts.saveAndFlush(UserAccount.withRoles(
                "admin-" + System.nanoTime() + "@example.com", "{bcrypt}h",
                Set.of(PlatformRole.ADMIN, PlatformRole.MEMBER)));
    }

    private UserAccount persistMember() {
        return accounts.saveAndFlush(UserAccount.member(
                "member-" + System.nanoTime() + "@example.com", "{bcrypt}h"));
    }

    private int auditCount(String subject, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM role_audit_log WHERE subject = ? AND action = ?",
                Integer.class, subject, action);
    }

    private void seedRefreshFamily(String subject) {
        // 이 계정에 살아 있는 Refresh Token 계열이 있다고 가정하고, 변경 후 폐기되는지 본다.
        jdbc.update("""
                INSERT INTO refresh_token_family (authorization_id, client_id, subject, expires_at, revoked)
                VALUES (?, 'gateway-bff', ?, ?, FALSE)
                """, "auth-" + subject, subject, Timestamp.from(Instant.now().plusSeconds(3600)));
    }

    private boolean familyRevoked(String subject) {
        return jdbc.queryForObject("SELECT revoked FROM refresh_token_family WHERE subject = ?",
                Boolean.class, subject);
    }

    @Test
    void changeStatusPersistsRevokesTokensAndAudits() {
        UserAccount admin = persistAdmin();
        UserAccount target = persistMember();
        seedRefreshFamily(target.getSubject());

        manageAccount.changeStatus(admin.getSubject(), target.getSubject(), AccountStatus.LOCKED);

        assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.LOCKED);
        assertThat(familyRevoked(target.getSubject())).isTrue();
        assertThat(auditCount(target.getSubject(), "STATUS_CHANGED")).isEqualTo(1);
    }

    @Test
    void grantAndRevokeRolePersistsAndAudits() {
        UserAccount admin = persistAdmin();
        UserAccount target = persistMember();

        manageAccount.grantRole(admin.getSubject(), target.getSubject(), PlatformRole.CREATOR);
        assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().hasRole(PlatformRole.CREATOR)).isTrue();

        manageAccount.revokeRole(admin.getSubject(), target.getSubject(), PlatformRole.CREATOR);
        assertThat(accounts.findBySubject(target.getSubject()).orElseThrow().hasRole(PlatformRole.CREATOR)).isFalse();

        assertThat(auditCount(target.getSubject(), "ROLE_CHANGED")).isEqualTo(2);
    }

    @Test
    void lastAdminRoleCannotBeRevoked() {
        // 깨끗한 상태에서 ADMIN을 정확히 한 명으로 만든다.
        jdbc.update("DELETE r FROM user_account_role r WHERE r.role = 'ADMIN'");
        UserAccount actingAdmin = persistAdmin();
        UserAccount soleAdmin = persistAdmin();
        // actingAdmin의 ADMIN을 떼어 soleAdmin만 ADMIN으로 남긴다.
        jdbc.update("DELETE r FROM user_account_role r JOIN user_account a ON a.id = r.user_account_id "
                + "WHERE a.subject = ? AND r.role = 'ADMIN'", actingAdmin.getSubject());

        assertThatThrownBy(() -> manageAccount.revokeRole(actingAdmin.getSubject(), soleAdmin.getSubject(),
                PlatformRole.ADMIN)).isInstanceOf(BusinessException.class);
        assertThat(accounts.findBySubject(soleAdmin.getSubject()).orElseThrow().hasRole(PlatformRole.ADMIN)).isTrue();
    }
}
