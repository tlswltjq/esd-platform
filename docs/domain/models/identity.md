# 계정과 역할 모델

[전체 안내](../model-guide.md) · [창작 작업 공간](studio.md#workspace) · [인증 계약](../../security.md)

Auth는 요청한 사람과 역할의 출처다. “구매자”, “창작자”, “판매자”가 항상 서로 다른 계정이라는 뜻은 아니다. 창작자 가입 계정은 `MEMBER`와 `CREATOR` 역할을 함께 가진다.

## UserAccount — 로그인 계정

저장: MySQL `user_account`와 역할 목록 `user_account_role`. 원본 모델이며 `BaseTimeEntity`를 상속한다. [모델](../../../apps/auth/src/main/java/com/stove/auth/core/domain/UserAccount.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`) | 숫자 계정 ID. access token의 `member_id`가 되어 주문·이용권의 구매자를 식별 |
| `subject` (`String`) | `usr_...` 형식의 인증 주체 식별자. 토큰의 주체와 Studio의 `ownerSubject`를 연결 |
| `email` (`String`) | 로그인 이메일. 생성 시 소문자로 저장하며 중복 가입을 검사 |
| `passwordHash` (`String`) | 비밀번호 검증용 해시. 비밀번호 원문이 아님 |
| `status` (`AccountStatus`) | `ACTIVE`만 `enabled()=true`. `LOCKED`, `DISABLED`도 정의되어 있으나 모델에 전환 메서드는 없음 |
| `roles` (`Set<PlatformRole>`) | `MEMBER` 구매자, `CREATOR` 창작자, `REVIEWER` 심사자, `ADMIN` 운영자. 역할 목록을 토큰에 전달 |

**만들고 쓰는 때:** RegisterAccount.registerMember()는 MEMBER 계정을, registerCreator()는 MEMBER+CREATOR 계정을 만든다.
가입과 함께 역할 감사 기록과 UserRegistered 이벤트를 저장한다. Studio는 로그인 JWT의 CREATOR 역할과 sub로 Project 소유자를 확인한다.
가입 이벤트로 Workspace를 생성하거나 별도 판매자 ID를 조회하지 않는다.

**예:** Auth가 발급한 member_id는 숫자 계정 ID이며 sub는 인증 주체다. 현재 Studio의 ownerSubject에는 sub를 직접 저장한다.

근거: [RegisterAccount](../../../apps/auth/src/main/java/com/stove/auth/core/service/RegisterAccount.java), [토큰 생성](../../../apps/auth/src/main/java/com/stove/auth/config/AuthSecurityConfig.java), [JWT 소유자로 Project 생성](../../../apps/studio/src/main/java/com/stove/studio/core/service/project/CreateProject.java). 기존 검증: [RegisterAccountTest](../../../apps/auth/src/test/java/com/stove/auth/core/service/RegisterAccountTest.java).

## RoleAuditLog — 역할 부여 기록

저장: MySQL `role_audit_log`. [모델](../../../apps/auth/src/main/java/com/stove/auth/core/domain/RoleAuditLog.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`) | 감사 기록 ID |
| `subject` (`String`) | 역할을 받은 계정의 인증 주체 |
| `actor` (`String`) | 부여한 주체. 가입 경로는 `system:signup` |
| `action` (`String`) | 어떤 역할 처리였는지 나타내는 행동명 |
| `roles` (`String`) | 당시 역할 목록의 문자열 기록 |
| `occurredAt` (`Instant`) | 기록한 시각 |

**만들고 쓰는 때:** 가입 시 부여한 역할의 근거를 남긴다. 현재 권한 판정은 `UserAccount.roles`와 토큰으로 하고, 이 로그는 과거에 무엇이 기록되었는지 확인하는 데 사용한다. 감사 테이블이 있다는 사실만으로 관리자 역할 변경 API까지 구현되어 있다고 해석하지 않는다.
