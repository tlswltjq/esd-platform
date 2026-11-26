package com.stove.auth.core.service;

import com.stove.auth.core.domain.AccountStatus;
import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.RoleAuditLog;
import com.stove.auth.core.domain.RoleAuditLogRepository;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.port.TokenRevocationPort;
import com.stove.common.contract.payload.AccountAccessChangedEvent;
import com.stove.common.messaging.outbox.OutboxRecorder;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자(ADMIN)의 계정 상태·역할 관리. 운영 안전 규칙을 판정한다.
 *
 * <ul>
 *   <li>요청자는 토큰의 역할과 별개로, 지금 DB에서도 활성(`ACTIVE`) 운영자(`ADMIN`)여야 한다(R7.1). 역할을 회수당하거나
 *       정지된 운영자의 남은 access token으로는 바꿀 수 없다.
 *   <li>자기 계정은 상태·역할을 바꿀 수 없다(스스로 잠그거나 권한을 빼는 사고 방지).
 *   <li>마지막 ADMIN의 ADMIN 역할은 회수할 수 없다(운영자 0명 방지).
 *   <li>상태·역할이 바뀌면 같은 트랜잭션에서 그 계정의 Refresh Token 계열을 폐기하고, 계정 접근 변경 사실
 *       ({@code AccountAccessChanged})을 Outbox에 쓴다(R7.6, 결정 180). 이미 발급된 access token은 받는 쪽(웹 BFF·API
 *       게이트웨이, 결정 225)이 이 사실로 끊는다. 이미 가진 역할의 부여처럼 바뀐 것이 없으면 감사·폐기·사실 모두 하지 않는다.
 *   <li>모든 변경을 {@code role_audit_log}에 남긴다.
 * </ul>
 *
 * <p>트랜잭션은 READ COMMITTED 로 돈다(D-061). 계정 행은 subject 순서로 잠그고, 역할(EAGER {@code @ElementCollection})은
 * 잠금 조회 뒤 잠그지 않는 별도 SELECT 로 읽힌다. REPEATABLE READ 에서는 먼저 잠근 계정의 역할 조회가 읽기 스냅샷을 고정해,
 * 뒤에서 잠금을 기다려 얻은 요청자의 역할을 회수 커밋 전 값으로 읽는다(회수 직후 운영자의 변경이 통과). READ COMMITTED 에서는
 * 문장마다 최신 커밋을 읽어, 잠금을 얻은 뒤의 역할 조회가 회수를 본다.
 *
 * <p>변경은 계정 행 배타 잠금으로 시작하고, 토큰 발급(코드 교환·갱신)은 역할을 읽기 전에 같은 행을 공유 잠금한다
 * ({@link UserAccountRepository#lockForIssuance}). 그래서 동시 발급은 변경보다 먼저 커밋됐거나(그 토큰의 {@code iat}가 더
 * 이르고 그 계열은 이 폐기에 걸린다) 변경 커밋을 기다린 뒤 새 역할을 읽는다. {@code changedAt}은 잠금을 잡고 폐기한
 * <b>뒤에</b> 잰다.
 */
@Service
@RequiredArgsConstructor
public class ManageAccount {

    private final UserAccountRepository accounts;
    private final RoleAuditLogRepository audits;
    private final TokenRevocationPort tokens;
    private final OutboxRecorder outbox;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changeStatus(String actorSubject, String targetSubject, AccountStatus next) {
        UserAccount target = requireDifferentTarget(actorSubject, targetSubject);
        try {
            target.changeStatus(next);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw new BusinessException(ErrorCode.CONFLICT, rejected.getMessage());
        }
        audits.save(RoleAuditLog.statusChanged(target, actorSubject));
        revokeAccess(target, AccountAccessChangedEvent.STATUS);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void grantRole(String actorSubject, String targetSubject, PlatformRole role) {
        UserAccount target = requireDifferentTarget(actorSubject, targetSubject);
        if (target.hasRole(role)) {
            return;
        }
        target.grantRole(role);
        audits.save(RoleAuditLog.roleChanged(target, actorSubject));
        revokeAccess(target, AccountAccessChangedEvent.ROLE);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revokeRole(String actorSubject, String targetSubject, PlatformRole role) {
        UserAccount target = requireDifferentTarget(actorSubject, targetSubject);
        if (role == PlatformRole.ADMIN && accounts.countByRole(PlatformRole.ADMIN) <= 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "마지막 운영자의 ADMIN 역할은 회수할 수 없습니다.");
        }
        try {
            target.revokeRole(role);
        } catch (IllegalStateException rejected) {
            throw new BusinessException(ErrorCode.CONFLICT, rejected.getMessage());
        }
        audits.save(RoleAuditLog.roleChanged(target, actorSubject));
        revokeAccess(target, AccountAccessChangedEvent.ROLE);
    }

    private void revokeAccess(UserAccount target, String changeKind) {
        tokens.revokeSubject(target.getSubject());
        recordAccessChanged(target, changeKind);
    }

    private void recordAccessChanged(UserAccount target, String changeKind) {
        outbox.record("UserAccount", target.getSubject(),
                AccountAccessChangedEvent.of(target.getSubject(), Instant.now(), changeKind));
    }

    /**
     * 요청자와 대상의 계정 행을 subject 순서로 배타 잠금한 뒤 판정한다. 요청자의 권한 확인과 그 요청자에 대한 다른 운영자의
     * 변경이 직렬화되고(확인 직후 ADMIN을 잃어도 진행하는 경합 방지), 고정된 순서라 서로를 바꾸는 두 요청이 교착하지 않는다.
     */
    private UserAccount requireDifferentTarget(String actorSubject, String targetSubject) {
        Map<String, Optional<UserAccount>> locked = new TreeMap<>();
        for (String subject : new TreeSet<>(List.of(actorSubject, targetSubject))) {
            locked.put(subject, accounts.lockForChange(subject));
        }
        requireActiveOperator(locked.get(actorSubject));
        if (actorSubject.equals(targetSubject)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "자기 계정은 상태·역할을 바꿀 수 없습니다.");
        }
        return locked.get(targetSubject)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대상 계정을 찾을 수 없습니다."));
    }

    /** R7.1: 토큰의 ADMIN 역할과 별개로 요청자 계정이 지금도 활성 운영자인지 DB에서 확인한다. */
    private void requireActiveOperator(Optional<UserAccount> actor) {
        boolean activeOperator = actor
                .map(value -> value.getStatus() == AccountStatus.ACTIVE && value.hasRole(PlatformRole.ADMIN))
                .orElse(false);
        if (!activeOperator) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "요청자가 활성 운영자가 아닙니다.");
        }
    }
}
