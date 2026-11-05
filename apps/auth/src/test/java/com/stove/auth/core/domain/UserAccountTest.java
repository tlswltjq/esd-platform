package com.stove.auth.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 계정의 상태 전환과 역할 변경 규칙을 도메인 수준에서 지킨다. */
class UserAccountTest {

    private UserAccount member() {
        return UserAccount.member("owner@example.com", "{bcrypt}hash");
    }

    // ── 상태 전환 ────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE와 LOCKED는 양방향으로 전환된다(정지·해제)")
    void activeAndLockedAreReversible() {
        UserAccount account = member();
        account.changeStatus(AccountStatus.LOCKED);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.LOCKED);
        account.changeStatus(AccountStatus.ACTIVE);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("ACTIVE·LOCKED에서 DISABLED로 종료할 수 있다")
    void canDisableFromActiveOrLocked() {
        UserAccount fromActive = member();
        fromActive.changeStatus(AccountStatus.DISABLED);
        assertThat(fromActive.getStatus()).isEqualTo(AccountStatus.DISABLED);

        UserAccount fromLocked = member();
        fromLocked.changeStatus(AccountStatus.LOCKED);
        fromLocked.changeStatus(AccountStatus.DISABLED);
        assertThat(fromLocked.getStatus()).isEqualTo(AccountStatus.DISABLED);
    }

    @Test
    @DisplayName("DISABLED는 되돌릴 수 없다(종료는 영구)")
    void disabledCannotBeReverted() {
        UserAccount account = member();
        account.changeStatus(AccountStatus.DISABLED);
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.LOCKED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("같은 상태로의 전환은 거절한다")
    void sameStatusIsRejected() {
        UserAccount account = member();
        assertThatThrownBy(() -> account.changeStatus(AccountStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 역할 변경 ────────────────────────────────────────────────

    @Test
    @DisplayName("역할을 부여하고 회수할 수 있다")
    void grantAndRevokeRole() {
        UserAccount account = member();
        account.grantRole(PlatformRole.CREATOR);
        assertThat(account.hasRole(PlatformRole.CREATOR)).isTrue();
        account.revokeRole(PlatformRole.CREATOR);
        assertThat(account.hasRole(PlatformRole.CREATOR)).isFalse();
    }

    @Test
    @DisplayName("기본 역할 MEMBER는 회수할 수 없다")
    void memberRoleCannotBeRevoked() {
        UserAccount account = member();
        assertThatThrownBy(() -> account.revokeRole(PlatformRole.MEMBER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("가지지 않은 역할은 회수할 수 없다")
    void revokingAbsentRoleIsRejected() {
        UserAccount account = member();
        assertThatThrownBy(() -> account.revokeRole(PlatformRole.ADMIN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 가진 역할을 다시 부여해도 중복되지 않는다")
    void grantingOwnedRoleIsIdempotent() {
        UserAccount account = member();
        account.grantRole(PlatformRole.CREATOR);
        account.grantRole(PlatformRole.CREATOR);
        assertThat(account.getRoles()).containsExactlyInAnyOrder(PlatformRole.MEMBER, PlatformRole.CREATOR);
    }
}
