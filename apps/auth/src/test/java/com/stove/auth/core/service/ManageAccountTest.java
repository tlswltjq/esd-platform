package com.stove.auth.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stove.auth.core.domain.AccountStatus;
import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.RoleAuditLog;
import com.stove.auth.core.domain.RoleAuditLogRepository;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.port.TokenRevocationPort;
import com.stove.common.web.error.BusinessException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 운영자의 계정 상태·역할 관리. 자기 보호와 마지막 ADMIN 보호, 즉시 토큰 폐기, 감사 기록을 지킨다. */
class ManageAccountTest {

    private final UserAccountRepository accounts = Mockito.mock(UserAccountRepository.class);
    private final RoleAuditLogRepository audits = Mockito.mock(RoleAuditLogRepository.class);
    private final TokenRevocationPort revocation = Mockito.mock(TokenRevocationPort.class);
    private ManageAccount service;

    private UserAccount admin;
    private UserAccount target;

    @BeforeEach
    void setUp() {
        service = new ManageAccount(accounts, audits, revocation);
        admin = UserAccount.withRoles("admin@example.com", "{bcrypt}h",
                Set.of(PlatformRole.ADMIN, PlatformRole.MEMBER));
        target = UserAccount.member("target@example.com", "{bcrypt}h");
        when(accounts.findBySubject(admin.getSubject())).thenReturn(Optional.of(admin));
        when(accounts.findBySubject(target.getSubject())).thenReturn(Optional.of(target));
    }

    // ── 상태 변경 ────────────────────────────────────────────────

    @Test
    @DisplayName("상태를 바꾸면 전환 후 토큰을 폐기하고 감사에 남긴다")
    void changeStatusRevokesAndAudits() {
        service.changeStatus(admin.getSubject(), target.getSubject(), AccountStatus.LOCKED);

        assertThat(target.getStatus()).isEqualTo(AccountStatus.LOCKED);
        verify(revocation).revokeSubject(target.getSubject());
        verify(audits).save(any(RoleAuditLog.class));
    }

    @Test
    @DisplayName("운영자는 자기 상태를 바꿀 수 없다")
    void cannotChangeOwnStatus() {
        assertThatThrownBy(() -> service.changeStatus(admin.getSubject(), admin.getSubject(), AccountStatus.LOCKED))
                .isInstanceOf(BusinessException.class);
        verify(revocation, never()).revokeSubject(anyString());
    }

    @Test
    @DisplayName("규칙을 어기는 전환(DISABLED 되돌림)은 거절한다")
    void illegalTransitionIsRejected() {
        target.changeStatus(AccountStatus.DISABLED);
        assertThatThrownBy(() -> service.changeStatus(admin.getSubject(), target.getSubject(), AccountStatus.ACTIVE))
                .isInstanceOf(BusinessException.class);
    }

    // ── 역할 변경 ────────────────────────────────────────────────

    @Test
    @DisplayName("역할을 부여하면 토큰을 폐기하고 감사에 남긴다")
    void grantRoleRevokesAndAudits() {
        service.grantRole(admin.getSubject(), target.getSubject(), PlatformRole.CREATOR);

        assertThat(target.hasRole(PlatformRole.CREATOR)).isTrue();
        verify(revocation).revokeSubject(target.getSubject());
        verify(audits).save(any(RoleAuditLog.class));
    }

    @Test
    @DisplayName("ADMIN 역할을 회수할 때 마지막 ADMIN이면 거절한다")
    void cannotRevokeLastAdmin() {
        UserAccount otherAdmin = UserAccount.withRoles("admin2@example.com", "{bcrypt}h",
                Set.of(PlatformRole.ADMIN, PlatformRole.MEMBER));
        when(accounts.findBySubject(otherAdmin.getSubject())).thenReturn(Optional.of(otherAdmin));
        // 플랫폼에 ADMIN이 이 한 명뿐이다.
        when(accounts.countByRole(PlatformRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.revokeRole(admin.getSubject(), otherAdmin.getSubject(), PlatformRole.ADMIN))
                .isInstanceOf(BusinessException.class);
        verify(revocation, never()).revokeSubject(otherAdmin.getSubject());
    }

    @Test
    @DisplayName("ADMIN이 둘 이상이면 한 명의 ADMIN 역할은 회수할 수 있다")
    void canRevokeAdminWhenMoreThanOne() {
        UserAccount otherAdmin = UserAccount.withRoles("admin2@example.com", "{bcrypt}h",
                Set.of(PlatformRole.ADMIN, PlatformRole.MEMBER));
        when(accounts.findBySubject(otherAdmin.getSubject())).thenReturn(Optional.of(otherAdmin));
        when(accounts.countByRole(PlatformRole.ADMIN)).thenReturn(2L);

        service.revokeRole(admin.getSubject(), otherAdmin.getSubject(), PlatformRole.ADMIN);

        assertThat(otherAdmin.hasRole(PlatformRole.ADMIN)).isFalse();
        verify(revocation).revokeSubject(otherAdmin.getSubject());
    }

    @Test
    @DisplayName("운영자는 자기 역할을 회수할 수 없다")
    void cannotRevokeOwnRole() {
        assertThatThrownBy(() -> service.revokeRole(admin.getSubject(), admin.getSubject(), PlatformRole.ADMIN))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("존재하지 않는 대상은 거절한다")
    void unknownTargetIsRejected() {
        when(accounts.findBySubject("usr_ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeStatus(admin.getSubject(), "usr_ghost", AccountStatus.LOCKED))
                .isInstanceOf(BusinessException.class);
    }
}
