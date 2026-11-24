package com.stove.auth.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import com.stove.common.contract.DomainEvent;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** 운영자의 계정 상태·역할 관리. 자기 보호와 마지막 ADMIN 보호, 즉시 토큰 폐기, 감사 기록을 지킨다. */
class ManageAccountTest {

    private final UserAccountRepository accounts = Mockito.mock(UserAccountRepository.class);
    private final RoleAuditLogRepository audits = Mockito.mock(RoleAuditLogRepository.class);
    private final TokenRevocationPort revocation = Mockito.mock(TokenRevocationPort.class);
    private final OutboxRecorder outbox = Mockito.mock(OutboxRecorder.class);
    private ManageAccount service;

    private UserAccount admin;
    private UserAccount target;

    @BeforeEach
    void setUp() {
        service = new ManageAccount(accounts, audits, revocation, outbox);
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

    // ── 계정 접근 변경 사실(R7.6, 결정 180) ───────────────────────────────

    private AccountAccessChangedEvent recordedFor(String subject) {
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outbox).record(eq("UserAccount"), eq(subject), event.capture());
        assertThat(event.getValue()).isInstanceOf(AccountAccessChangedEvent.class);
        return (AccountAccessChangedEvent) event.getValue();
    }

    @Test
    @DisplayName("상태를 바꾸면 Refresh 계열 폐기 뒤 STATUS 접근 변경 사실을 Outbox에 쓴다")
    void statusChangeRecordsAccessChangedAfterRevocation() {
        service.changeStatus(admin.getSubject(), target.getSubject(), AccountStatus.LOCKED);

        AccountAccessChangedEvent event = recordedFor(target.getSubject());
        assertThat(event.subject()).isEqualTo(target.getSubject());
        assertThat(event.changeKind()).isEqualTo(AccountAccessChangedEvent.STATUS);
        assertThat(event.changedAt()).isNotNull();
        InOrder order = inOrder(revocation, outbox);
        order.verify(revocation).revokeSubject(target.getSubject());
        order.verify(outbox).record(eq("UserAccount"), eq(target.getSubject()), any(DomainEvent.class));
    }

    @Test
    @DisplayName("새 역할 부여와 회수는 ROLE 접근 변경 사실을 쓴다")
    void roleChangesRecordAccessChanged() {
        service.grantRole(admin.getSubject(), target.getSubject(), PlatformRole.CREATOR);
        assertThat(recordedFor(target.getSubject()).changeKind()).isEqualTo(AccountAccessChangedEvent.ROLE);

        Mockito.clearInvocations(outbox);
        service.revokeRole(admin.getSubject(), target.getSubject(), PlatformRole.CREATOR);
        assertThat(recordedFor(target.getSubject()).changeKind()).isEqualTo(AccountAccessChangedEvent.ROLE);
    }

    @Test
    @DisplayName("이미 가진 역할의 부여는 감사·토큰 폐기·접근 변경 사실 모두 하지 않는다")
    void grantingAHeldRoleRecordsNothing() {
        service.grantRole(admin.getSubject(), target.getSubject(), PlatformRole.MEMBER);

        verify(outbox, never()).record(anyString(), anyString(), any(DomainEvent.class));
        verify(revocation, never()).revokeSubject(anyString());
        verify(audits, never()).save(any(RoleAuditLog.class));
    }

    @Test
    @DisplayName("거절된 변경은 접근 변경 사실을 쓰지 않는다")
    void rejectedChangesRecordNothing() {
        assertThatThrownBy(() -> service.changeStatus(admin.getSubject(), admin.getSubject(), AccountStatus.LOCKED))
                .isInstanceOf(BusinessException.class);
        verify(outbox, never()).record(anyString(), anyString(), any(DomainEvent.class));
    }

    // ── 요청자의 현재 권한(R7.1) ──────────────────────────────────────

    @Test
    @DisplayName("요청자가 DB에서 ADMIN을 잃었으면 토큰에 ADMIN이 있어도 403이고 아무것도 바꾸지 않는다")
    void actorWithoutAdminInTheDatabaseIsForbidden() {
        UserAccount former = UserAccount.member("former@example.com", "{bcrypt}h");
        when(accounts.findBySubject(former.getSubject())).thenReturn(Optional.of(former));

        assertThatThrownBy(() -> service.changeStatus(former.getSubject(), target.getSubject(), AccountStatus.LOCKED))
                .isInstanceOfSatisfying(BusinessException.class,
                        rejected -> assertThat(rejected.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(target.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(revocation, never()).revokeSubject(anyString());
        verify(outbox, never()).record(anyString(), anyString(), any(DomainEvent.class));
    }

    @Test
    @DisplayName("정지된 운영자와 DB에 없는 요청자도 403이다")
    void lockedOrUnknownActorIsForbidden() {
        UserAccount locked = UserAccount.withRoles("locked@example.com", "{bcrypt}h", Set.of(PlatformRole.ADMIN, PlatformRole.MEMBER));
        locked.changeStatus(AccountStatus.LOCKED);
        when(accounts.findBySubject(locked.getSubject())).thenReturn(Optional.of(locked));
        when(accounts.findBySubject("usr_gone")).thenReturn(Optional.empty());

        for (String actor : new String[]{locked.getSubject(), "usr_gone"}) {
            assertThatThrownBy(() -> service.grantRole(actor, target.getSubject(), PlatformRole.CREATOR))
                    .isInstanceOfSatisfying(BusinessException.class,
                            rejected -> assertThat(rejected.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        assertThat(target.hasRole(PlatformRole.CREATOR)).isFalse();
    }
}
