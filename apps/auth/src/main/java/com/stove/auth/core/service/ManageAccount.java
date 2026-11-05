package com.stove.auth.core.service;

import com.stove.auth.core.domain.AccountStatus;
import com.stove.auth.core.domain.PlatformRole;
import com.stove.auth.core.domain.RoleAuditLog;
import com.stove.auth.core.domain.RoleAuditLogRepository;
import com.stove.auth.core.domain.UserAccount;
import com.stove.auth.core.domain.UserAccountRepository;
import com.stove.auth.core.port.TokenRevocationPort;
import com.stove.common.web.error.BusinessException;
import com.stove.common.web.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자(ADMIN)의 계정 상태·역할 관리. ADMIN 권한 자체는 게이트웨이가 거르고, 이 서비스는 운영 안전
 * 규칙을 판정한다.
 *
 * <ul>
 *   <li>자기 계정은 상태·역할을 바꿀 수 없다(스스로 잠그거나 권한을 빼는 사고 방지).
 *   <li>마지막 ADMIN의 ADMIN 역할은 회수할 수 없다(운영자 0명 방지).
 *   <li>상태·역할이 바뀌면 그 계정의 Refresh Token을 즉시 폐기한다(변경 즉시 효력).
 *   <li>모든 변경을 {@code role_audit_log}에 남긴다.
 * </ul>
 *
 * <p>기존 access token은 수명(기본 15분)까지 유효하다. 이는 access token의 기존 계약이다.
 */
@Service
@RequiredArgsConstructor
public class ManageAccount {

    private final UserAccountRepository accounts;
    private final RoleAuditLogRepository audits;
    private final TokenRevocationPort tokens;

    @Transactional
    public void changeStatus(String actorSubject, String targetSubject, AccountStatus next) {
        UserAccount target = requireDifferentTarget(actorSubject, targetSubject);
        try {
            target.changeStatus(next);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw new BusinessException(ErrorCode.CONFLICT, rejected.getMessage());
        }
        audits.save(RoleAuditLog.statusChanged(target, actorSubject));
        tokens.revokeSubject(targetSubject);
    }

    @Transactional
    public void grantRole(String actorSubject, String targetSubject, PlatformRole role) {
        UserAccount target = requireDifferentTarget(actorSubject, targetSubject);
        target.grantRole(role);
        audits.save(RoleAuditLog.roleChanged(target, actorSubject));
        tokens.revokeSubject(targetSubject);
    }

    @Transactional
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
        tokens.revokeSubject(targetSubject);
    }

    private UserAccount requireDifferentTarget(String actorSubject, String targetSubject) {
        if (actorSubject.equals(targetSubject)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "자기 계정은 상태·역할을 바꿀 수 없습니다.");
        }
        return accounts.findBySubject(targetSubject)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "대상 계정을 찾을 수 없습니다."));
    }
}
