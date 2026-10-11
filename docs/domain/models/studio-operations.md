# 창작 자동화·테스터·알림 모델

[전체 안내](../model-guide.md) · [창작·출시 모델](studio.md)

이 모델들은 Studio에서 파일 업로드·테스트·결과 통보를 운영할 때 사용한다. 모두 MySQL 저장 모델이다. 일반 고객의 구매 이용권과 창작 도구 접근 권한을 구별한다.

## ProjectCredential — 프로젝트 작업용 인증 정보

저장: `project_credential`. [모델](../../../apps/studio/src/main/java/com/stove/studio/core/domain/access/ProjectCredential.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `gameId`, `workspaceId` (`Long`) | 인증 정보 ID와 사용할 프로젝트·공간 범위 |
| `name` (`String`) | 사람이 구별하는 이름 |
| `secretHash`, `tokenPrefix` (`String`) | 검증용 토큰 해시와 표시용 앞부분. 원문 전체를 이 필드에 저장하지 않음 |
| `status` (`ProjectCredentialStatus`) | `ACTIVE` 또는 `REVOKED` |
| `expiresAt`, `lastUsedAt` (`Instant`) | 만료 시각·마지막 사용 시각 |
| `allowedRepository`, `allowedRef`, `allowedPlatform` (`String`) | 신뢰한 CI 인증에서 허용한 저장소·브랜치/태그·플랫폼 |
| `releaseAllowed` (`boolean`) | 신뢰 정책을 충족해 출시 작업을 허용하는지 |
| `sourceProvider`, `sourceEnvironment` (`String`) | 인증을 제공한 CI와 실행 환경 |
| `oidcTokenId` (`String`) | CI의 OIDC 인증을 중복 교환하지 않기 위한 식별값 |

**만들고 쓰는 때:** 창작자가 프로젝트용 인증 정보를 발급하거나, CI의 OIDC 토큰을 검증해 제한된 인증 정보로 교환한다. OIDC는 “외부 CI가 이 실행의 신원을 증명하는 방식”으로 읽으면 된다. 요청 시 프로젝트 범위·폐기·만료 등을 확인하고 사용 시각을 갱신한다. 기본 발급과 신뢰 정책을 통한 발급의 권한이 동일하다고 가정하지 않는다.

[IssuedProjectCredential](../../../apps/studio/src/main/java/com/stove/studio/core/domain/access/IssuedProjectCredential.java)은 `credential`과 원문 `token`을 함께 돌려주는 발급 결과다. 저장 모델과 발급 응답을 분리해 둔다. 근거: [ProjectCredentialService](../../../apps/studio/src/main/java/com/stove/studio/core/service/access/ProjectCredentialService.java), [CiOidcExchangeService](../../../apps/studio/src/main/java/com/stove/studio/core/service/access/CiOidcExchangeService.java).

## CiTrustPolicy — 신뢰할 CI 실행의 조건

저장: `ci_trust_policy`. [모델](../../../apps/studio/src/main/java/com/stove/studio/core/domain/access/CiTrustPolicy.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `gameId`, `workspaceId` (`Long`) | 정책과 적용 범위 |
| `provider`, `repository` (`String`) | 허용한 CI 제공자·소스 저장소 |
| `refPattern`, `platform` (`String`) | 허용할 소스 참조 패턴과 대상 플랫폼 |
| `protectedRefPattern`, `requiredEnvironment` (`String`) | 출시 권한 부여에 추가로 필요한 보호된 소스 참조·실행 환경 |

**만들고 쓰는 때:** 창작자가 신뢰 정책을 등록하고, CI 인증 교환 시 해당 정책을 대조한다. 일치한 실행에만 `ProjectCredential`을 발급하며 출시 권한은 추가 조건으로 결정한다. [CiTrustPolicyService](../../../apps/studio/src/main/java/com/stove/studio/core/service/access/CiTrustPolicyService.java)

## InternalTesterGrant — 내부 채널 설치 권한

저장: `internal_tester_grant`. [모델](../../../apps/studio/src/main/java/com/stove/studio/core/domain/access/InternalTesterGrant.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `gameId`, `workspaceId` (`Long`) | 권한 기록과 프로젝트·공간 |
| `testerSubject` (`String`) | 테스터의 인증 주체. 커머스의 숫자 `memberId`와 구별 |
| `channel` (`ReleaseChannel`) | 접근할 `DEV`, `TEST`, `STAGE` 채널. LIVE는 허용하지 않음 |
| `active` (`boolean`) | 현재 유효한지. 재활성화·회수 가능 |

**만들고 쓰는 때:** 창작자가 테스터·프로젝트·채널별 권한을 부여한다. 설치 요청은 해당 빌드가 채널에 공개됐는지와 유효한 권한이 있는지를 확인한다. 결과 [TesterInstallation](../../../apps/studio/src/main/java/com/stove/studio/core/domain/access/TesterInstallation.java)은 `buildId`, `channel`, `downloadUrl`, `expiresAt`을 담는다. 고객의 `License` 구매 흐름과 별도다. [InternalTesterService](../../../apps/studio/src/main/java/com/stove/studio/core/service/access/InternalTesterService.java)

## BuildWebhookSubscription — 빌드 결과를 받을 주소

저장: `build_webhook_subscription`. [모델](../../../apps/studio/src/main/java/com/stove/studio/core/domain/notification/BuildWebhookSubscription.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `gameId`, `workspaceId` (`Long`) | 구독 ID·대상 프로젝트·공간 |
| `endpointUrl` (`String`) | 결과를 보낼 HTTPS 주소 |
| `encryptedSecret` (`String`) | 통보 서명에 사용할 비밀값의 암호문 |
| `active` (`boolean`) | 새 결과를 통보할 유효한 구독인지 |

**만들고 쓰는 때:** 창작자가 외부 수신 주소를 등록한다. Webhook은 상태 변경 시 지정된 주소로 서버가 HTTP 요청을 보내는 알림 방식이다. 등록 결과 [RegisteredBuildWebhook](../../../apps/studio/src/main/java/com/stove/studio/core/domain/notification/RegisteredBuildWebhook.java)은 `subscription`, `signingSecret`을 반환한다. 구독 설정과 개별 발송 기록은 분리한다.

## BuildWebhookDelivery — 알림 한 건의 발송 진행

저장: `build_webhook_delivery`. [모델](../../../apps/studio/src/main/java/com/stove/studio/core/domain/notification/BuildWebhookDelivery.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `subscriptionId`, `buildId` (`Long`) | 발송 건·수신 구독·관련 빌드 |
| `eventType`, `payload` (`String`) | 무슨 결과인지와 전송할 JSON 내용 |
| `status` (`WebhookDeliveryStatus`) | `PENDING` 대기, `RETRY` 재시도, `DELIVERED` 성공, `FAILED` 최종 실패 |
| `attemptCount` (`int`) | 발송 시도 횟수. 성공한 시도도 포함 |
| `nextAttemptAt`, `deliveredAt` (`Instant`) | 다음 시도 예약·성공 시각 |
| `lastError` (`String`) | 마지막 실패 설명 |

**만들고 쓰는 때:** 빌드 결과가 생기면 활성 구독별로 발송할 행을 만든다. 예약된 발송을 실행하고 HTTP 성공/실패를 기록한다. 실패는 간격을 두고 재시도하며 현재 8회 시도부터 최종 실패다. 빌드 검증 성공과 알림 전송 성공은 별개 상태다. [BuildWebhookService](../../../apps/studio/src/main/java/com/stove/studio/core/service/notification/BuildWebhookService.java)

Studio의 `AuditLog`는 공개·예약·권한 변경 같은 업무 행동을 남긴다. 필드와 용도는 [공통 감사 기록](shared.md#audit-log)을 참고한다.
