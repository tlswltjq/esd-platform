# 설계: Review

> 상태: 작성 중(결정 150·178). 이벤트의 뜻·소비자·키·순서, 흐름 단계와 실패 처리, 모듈 간 정합 조건은 [플랫폼 design](../platform/design.md)이 정본이다. 경로 줄임: `review:` = `apps/review/src/main/java/com/stove/review/`, `review/db/Vn` = `apps/review/src/main/resources/db/migration/Vn__*.sql`. 줄 번호는 2026-11-10 `docs/spec-foundation` 기준.

## 아키텍처 위치

- 경계·의존 방향은 [P1](../platform/requirements.md#p1-모듈-경계와-의존-방향)을 따른다. Review는 자기 스키마 `stove_review`만 읽고 쓴다(P1.1-2).
- 동기 의존: 받는 쪽만 있다(Gateway → Review `/api/v1/reviews/**`). Review는 다른 업무 모듈을 HTTP로 부르지 않는다. 외부 공급자는 객체 저장소 하나(읽기 전용).
- 비동기 의존: Studio ⇄ Review 쌍 S1([design "비동기 의존"](../platform/design.md#비동기-의존-요청--사실-응답-쌍-p13-2)). Review는 `stove.studio.v1`을 그룹 `review-project`로 받고 `stove.review.v1`에 낸다. `ProjectReviewApproved`의 소비자는 Studio(`studio-project`)와 Catalog(`catalog-review-approval`, 결정 167)다.
- 패키지:

| 층 | 클래스 | 맡는 일 |
|---|---|---|
| `api.listener` | `StudioEventListener`, `ReviewSubmissionMapper`, `ReviewWithdrawalMapper` | 레코드 → `EventEnvelope` → (`eventType`, `schemaVersion`)으로 Mapper 선택 → 봉투·본문·키 검증 → 내부 입력 |
| `api.controller` | `ReviewController`, `dto/*` | 5개 업무 API, JWT `sub` → `actor` |
| `core.domain` | `ProjectReview`, `ReviewStatus`, `ReviewSubmission`, `ReviewWithdrawal`, `ReviewWithdrawalInput`, `ReviewWithdrawalValues`, `ReviewGenreValues`, `ReviewRequestGate`, 저장소 인터페이스 | 상태 전이·값 검증 |
| `core.service` | `ReceiveProjectReview`, `ReceiveReviewWithdrawal`, `DecideProjectReview`, `ReadProjectReviews`, `ReadReviewBuildDownload` | 잠금·Inbox·상태 변경·Outbox 트랜잭션 조율 |
| `core.port` | `ReviewBuildStorage` | 제출 빌드 읽기 URL 발급 계약 |
| `adapter.storage` | `S3ReviewBuildStorage` | 객체 저장소 HEAD·presign |
| `config` | `SecurityConfig`, `StorageConfiguration`, `ReviewStorageProperties`, `RequestJsonConfig` | 설정 바인딩·조립 |

## 데이터 모델

DB: MySQL `stove_review`. 다른 서비스 테이블 조인·외부 FK 없음.

### `project_review` (V10·V11, 업무 정본)

| 열 | 형식 | 뜻 |
|---|---|---|
| `review_request_id` | BIGINT PK | Studio 발급 요청 ID |
| `request_event_id` | VARCHAR(64) NOT NULL | 최초 접수한 `ProjectReviewRequested`의 `eventId`(결과 이벤트의 `requestEventId`) |
| `project_id`·`product_code`·`owner_subject`·`content_version` | NOT NULL, 갱신 안 함 | 스냅샷에서 뽑은 조회·결과 연결 값 |
| `snapshot` | LONGTEXT NOT NULL, 갱신 안 함 | 받은 `ProjectSnapshot` 전체 JSON |
| `status` | VARCHAR(20) NOT NULL | `ReviewStatus` |
| `actor`·`decided_at` | NULL 가능 | 최초 판정자 JWT `sub`·판정 시각(Review 시계) |
| `feedback` | VARCHAR(2000) | 반려 사유 |
| `withdrawn_by`·`withdrawal_reason`·`withdrawn_at` | NULL 가능 | 철회자·선택 사유·철회 시각(Review 시계) |
| `requested_at` | DATETIME(6) NOT NULL | 요청 이벤트의 `occurredAt`(Studio 사실 시각, 표시·정렬 값만) |
| `active_project_id` | 생성 열 STORED | `status='REQUESTED'`이면 `project_id`, 아니면 NULL |
| `entity_version` | BIGINT | JPA 낙관 버전 |
| `created_at`·`updated_at` | DATETIME(6) | Review 시계 수신·변경 시각 |

제약(지금): PK, `uk_review_active_project(active_project_id)`(V11), 인덱스 `idx_project_review_status(status, requested_at, review_request_id)`·`idx_project_review_project(project_id, requested_at, review_request_id)`.

제약(목표, RV-T1·MC-R1). 상태별로 어떤 칸이 차는지는 하위 상태가 아니라 상태의 속성이고, DB가 지킨다:

```sql
ALTER TABLE project_review
  ADD CONSTRAINT chk_project_review_status
    CHECK (status IN ('REQUESTED','APPROVED','REJECTED','WITHDRAWN')),
  ADD CONSTRAINT chk_project_review_decision
    CHECK ((status IN ('APPROVED','REJECTED')) = (actor IS NOT NULL AND decided_at IS NOT NULL)
       AND (actor IS NULL) = (decided_at IS NULL)),
  ADD CONSTRAINT chk_project_review_feedback
    CHECK ((status = 'REJECTED') = (feedback IS NOT NULL)),
  ADD CONSTRAINT chk_project_review_withdrawn
    CHECK ((status = 'WITHDRAWN') = (withdrawn_by IS NOT NULL AND withdrawn_at IS NOT NULL)
       AND (withdrawal_reason IS NULL OR status = 'WITHDRAWN'));
```

이행 전 확인: 위 조건을 어기는 기존 행 수를 세는 SELECT가 0행이어야 하고, 0이 아니면 임의 정리 없이 멈춘다(V11 전환과 같은 방식).

- Project·버전·빌드 조합에는 유일 제약이 없다. 같은 버전의 반려·철회 뒤 재요청도 새 ID로 접수된다(결정 59).
- `genres`는 정의된 코드 1~3개(`GenreCatalog.published`, 선택 중지 코드 허용)다. 이미 제출한 장르가 나중에 선택 중지돼도 기존 심사는 이어간다.

### `review_withdrawal` (V11, 철회 명령 보관)

| 열 | 뜻 |
|---|---|
| `withdrawal_id` PK | Studio 철회 명령 ID(`ProjectWithdrawalRequest.withdrawalId`) |
| `request_id`·`project_id`·`content_version`·`product_code`·`owner_subject`·`reason` | 명령 내용(불변). `requireSame`이 같은 ID의 다른 내용을 거절 |
| `requested_at` | 철회 이벤트 `occurredAt`(Studio 시각, 값만) |
| `resolved` | BOOLEAN — 미해결(`false`) / 해결(`true`). 해결 결과(철회됨·거절)는 저장하지 않고 `project_review.status`가 정본 |
| `created_at`·`updated_at` | Review 시계. `created_at`이 원요청 없는 철회 대기 시간의 기준(R10.1) |

인덱스 `idx_review_withdrawal_pending(request_id, resolved, withdrawal_id)`. 목표: `chk_review_withdrawal_resolved CHECK (resolved IN (0,1))`(MySQL BOOLEAN은 TINYINT(1)이라 다른 값을 막지 않는다, RV-T1). 목표: 이 열을 상태 열 `state`(enum `ReviewWithdrawalState {PENDING, RESOLVED, CONFLICTED}`) + `chk_review_withdrawal_state CHECK (state IN ('PENDING','RESOLVED','CONFLICTED'))`로 바꾼다([결정 183](../../../docs/decisions.md#isolate-conflicted-withdrawal), RV-T7). 옛 `resolved=0/1`은 `PENDING`/`RESOLVED`로 옮긴다.

### `review_request_gate` (V11)

`request_id` PK 하나. 원요청 전에도 잠글 대상을 만든다(`ensure` = `INSERT … ON DUPLICATE KEY UPDATE`, `lock` = `PESSIMISTIC_WRITE`). 상태 없음.

### 공통 표

- `outbox_event`(V1~V3, 유일 `uk_outbox_event_id`), `processed_event`(V1, `uk_processed_event(event_id, consumer_group)`).
- 목표: 실패 보관·키 정지 표(결정 160, 표 구조는 common messaging 설계), `outbox_republish`(결정 161). RV-T8.
- 옛 표(V4~V9의 제출·심사 사건·감사·등급 표)는 역사 자료로 보존하고 새 업무가 읽거나 쓰지 않는다. 자동 이관하지 않는다.

## 상태 모델

### `ProjectReview.status` — `ReviewStatus {REQUESTED, APPROVED, REJECTED, WITHDRAWN}`

| 값 | 뜻 | 차는 칸 |
|---|---|---|
| (없음) | 이 `reviewRequestId`의 행이 없다(게이트·미해결 철회는 있을 수 있음) | — |
| `REQUESTED` | 접수됨, 최종 결과 없음. 프로젝트의 활성 심사 자리를 쥔다 | `active_project_id` |
| `APPROVED` | 심사자가 승인함. 최종 | `actor`·`decided_at` |
| `REJECTED` | 심사자가 사유와 함께 반려함. 최종 | `actor`·`decided_at`·`feedback` |
| `WITHDRAWN` | 판정 전에 창작자 철회가 먼저 커밋됨. 최종, 반려 아님 | `withdrawn_by`·`withdrawn_at`(·`withdrawal_reason`) |

최종 상태 셋 사이의 전이는 없다(R5.4).

#### 상태 × 트리거 (`project_review` 한 행 기준)

결과 표기: `허용 → 상태` / `무변화(멱등)` / `거절(코드 또는 영구 실패)`. "발행"은 같은 트랜잭션 Outbox. 영구 실패 = 재시도 없이 실패 보관·키 정지(R2.5).

| 트리거 \ 상태 | (없음) | `REQUESTED` | `APPROVED` | `REJECTED` | `WITHDRAWN` |
|---|---|---|---|---|---|
| E1 `ProjectReviewRequested`, 같은 `reviewRequestId`, 같은 스냅샷 | 허용 → `REQUESTED`. 미해결 철회가 있으면 같은 트랜잭션에서 E4를 적용해 → `WITHDRAWN`, 철회마다 `ProjectReviewWithdrawn` 발행 | 무변화(멱등) | 무변화(멱등) | 무변화(멱등) | 무변화(멱등, 다시 열지 않음) |
| E2 같은 `reviewRequestId`, 다른 스냅샷 | E1과 같음(비교할 저장 스냅샷이 없다) | 거절(영구 실패, 스냅샷 충돌) | 거절(영구 실패) | 거절(영구 실패) | 거절(영구 실패) |
| E3 같은 Project의 다른 `reviewRequestId` 원요청(이 행 기준) | 해당 행 없음 — 새 행은 E1로 처리 | 거절(영구 실패, `uk_review_active_project`), 이 행 무변화 | 무변화(새 행은 E1) | 무변화(새 행은 E1) | 무변화(새 행은 E1) |
| E4 `ProjectReviewWithdrawalRequested`, 새 `withdrawalId`, 대상 일치 | 허용: 철회 보관(미해결), 심사 행은 계속 없음, 발행 없음 | 허용 → `WITHDRAWN`, 철회 해결, `ProjectReviewWithdrawn` 발행 | 무변화 + 철회 해결, `ProjectReviewWithdrawalDenied(targetStatus=APPROVED)` 발행 | 무변화 + 철회 해결, `ProjectReviewWithdrawalDenied(targetStatus=REJECTED)` 발행 | 무변화 + 철회 해결, `ProjectReviewWithdrawn`을 이 `withdrawalId`로 발행 |
| E5 같은 `withdrawalId`, 같은 내용(다른 `eventId` 또는 재전달) | 무변화(보관 유지) | 해결된 명령이면 무변화(멱등). 미해결로 남아 있으면 E4로 해결 | 해결된 명령이면 무변화. 미해결이면 E4 | 같음 | 같음 |
| E6 같은 `withdrawalId`, 다른 내용 | 거절(영구 실패), 보관 내용 유지 | 거절(영구 실패) | 거절(영구 실패) | 거절(영구 실패) | 거절(영구 실패) |
| E7 새 `withdrawalId`, 대상(`projectId`·`contentVersion`·`productCode`·`ownerSubject`)이 행과 다름 | 대조할 행이 없어 E4로 보관. 원요청이 나중에 오면 E1에서 원요청은 접수하고 이 철회는 `CONFLICTED`([결정 183](../../../docs/decisions.md#isolate-conflicted-withdrawal)) | 거절(영구 실패), 무변화, 철회 행도 저장 안 함 | 거절(영구 실패) | 거절(영구 실패) | 거절(영구 실패) |
| H1 `POST …/approve` | 거절(404 `NOT_FOUND`) | 허용 → `APPROVED`, `ProjectReviewApproved` 발행 | 무변화(200, 최초 `actor`·`decidedAt`) | 거절(409 `CONFLICT`) | 거절(409 `CONFLICT`) |
| H2 `POST …/reject`, 유효한 `feedback` | 거절(404) | 허용 → `REJECTED`, `ProjectReviewRejected` 발행 | 거절(409) | 같은 사유면 무변화(200), 다른 사유면 거절(409) | 거절(409) |
| H3 `POST …/reject`, `feedback` 없음·공백·2000자 초과 또는 모르는 본문 칸 | 거절(400 `INVALID_REQUEST`, 본문 검증이 먼저) | 거절(400), 무변화 | 거절(400) | 거절(400) | 거절(400) |
| H4 `GET` 상세·목록·`build-download` | 상세·열람 404, 목록은 빈 결과 | 무변화(조회; 열람은 200·409·503) | 무변화 | 무변화 | 무변화(종료 심사 URL 발급 범위는 M-RV-4) |
| L1 Mapper 계약 위반(봉투 헤더·`eventId`/`eventType` 불일치·키 ≠ `productCode`·형식·읽을 수 없는 `schemaVersion`) | 거절(영구 실패, 업무 처리 전, Inbox 표시 없음) | 같음, 무변화 | 같음 | 같음 | 같음 |
| L2 `stove.studio.v1`의 다른 `eventType` | 무변화(건너뜀 지표, R8.7) | 무변화 | 무변화 | 무변화 | 무변화 |
| J1 예약: 원요청 없는 철회 게이지 계산 | 무변화(미해결 철회를 셈) | 무변화 | 무변화 | 무변화 | 무변화 |
| O1 ADMIN 실패 보관 재투입 | 재투입 레코드의 트리거 행(E1~E7·L1)과 같음 | 같음 | 같음 | 같음 | 같음 |
| O2 ADMIN 재발행·`DEAD` 회수 | 무변화(같은 `eventId`·본문·키로 Outbox만) | 무변화 | 무변화 | 무변화 | 무변화 |
| O3 판정·철회를 바꾸는 운영자 명령 | 거절(경로 없음, R5.4) | 거절(경로 없음) | 거절(경로 없음) | 거절(경로 없음) | 거절(경로 없음) |

#### `review_withdrawal` — 미해결 / 해결

| 트리거 \ 상태 | (없음) | 미해결(`resolved=false`) | 해결(`resolved=true`) |
|---|---|---|---|
| 철회 이벤트, 이 `withdrawalId` 처음, 심사 행 없음 | 허용 → 미해결 | — (이 ID 행이 이미 있으면 아래 행) | — |
| 철회 이벤트, 이 `withdrawalId` 처음, 심사 행 있고 대상 일치 | 허용 → 해결(같은 트랜잭션, E4) | — | — |
| 철회 이벤트, 이 `withdrawalId` 처음, 심사 행과 대상 불일치 | 거절(영구 실패, 저장 안 함) | — | — |
| 같은 `withdrawalId`·같은 내용 | — | 심사 행 있으면 → 해결(E4), 없으면 무변화 | 무변화(멱등) |
| 같은 `withdrawalId`·다른 내용 | — | 거절(영구 실패) | 거절(영구 실패) |
| 원요청 접수(E1), 대상 일치 | — | 허용 → 해결, `ProjectReviewWithdrawn` 발행 | 무변화 |
| 원요청 접수(E1), 대상 불일치 | — | 허용 → `CONFLICTED`(적용 안 함, 결과 이벤트 없음, 경보 게이지 +1). 원요청은 접수([결정 183](../../../docs/decisions.md#isolate-conflicted-withdrawal)) | 무변화 |
| 예약: 게이지 | — | 무변화(원요청 없는 철회로 셈) | 무변화 |

"—"는 그 상태에서 그 트리거가 성립하지 않는다는 뜻이다(행 존재 여부가 트리거 조건이다).

#### 잠금 순서

- 접수(E1~E3): `review_request_gate` 잠금 → `findById` → (없으면) Inbox → 행 생성·미해결 철회 해결 → Outbox.
- 철회(E4~E7): 게이트 잠금 → 철회 행 조회 → Inbox → 철회 보관 → `project_review` 행 잠금 → 해결 → Outbox.
- 판정(H1·H2): `project_review` 행 잠금만(행이 있어야 판정할 수 있다). 철회는 게이트 다음에 같은 행을 잠그므로 판정과 철회가 행 잠금에서 직렬화되고, 판정은 잠금을 하나만 쥐어 교착 순서가 없다.
- 먼저 커밋된 쪽이 최종이다(결정 53). 기준은 커밋이며 버튼·Studio 접수·잠금 획득 시각이 아니다.

## API 계약

모든 업무 API는 Gateway 경로 `/api/v1/reviews/**`이고 Gateway·Review 모두 `REVIEWER` 또는 `ADMIN`을 요구한다. 응답은 공통 `ApiResponse`. 본문의 모르는 칸은 400(`FAIL_ON_UNKNOWN_PROPERTIES`).

| 메서드·경로 | 요청 | 응답 `data` | 오류 | 멱등 |
|---|---|---|---|---|
| `GET /api/v1/reviews?status&projectId&page&size` | `status` ∈ `ReviewStatus`, page≥0, size 1~100(기본 20) | `ProjectReviewPageResponse`(요약 목록·페이지) | 400 잘못된 `status`, 401, 403 | 조회 |
| `GET /api/v1/reviews/{reviewRequestId}` | — | `ProjectReviewResponse`(스냅샷 표시 값 `ReviewSnapshotResponse`, 상태, `actor`·`decidedAt`·`feedback`, 철회 칸). `storagePath` 없음 | 401·403·404 | 조회 |
| `GET /api/v1/reviews/{reviewRequestId}/build-download` | — | `BuildDownload {url, expiresAt, fileSize, checksum}`, `Cache-Control: no-store` | 401·403·404, 409 `CONFLICT`(버킷 밖·객체 없음·크기 불일치), 503 `UPSTREAM_UNAVAILABLE` | 호출마다 새 URL |
| `POST /api/v1/reviews/{reviewRequestId}/approve` | 본문 없음 | `ProjectReviewResponse` | 401·403·404, 409(반대 판정·철회됨) | 상태로 멱등: 이미 `APPROVED`면 200·새 이벤트 없음 |
| `POST /api/v1/reviews/{reviewRequestId}/reject` | `{feedback}` 1~2000자, 공백 불가 | `ProjectReviewResponse` | 400, 401·403·404, 409(반대 판정·다른 사유·철회됨) | 같은 사유 재시도는 200·새 이벤트 없음 |

- `actor`는 `@CurrentSubject`(검증된 JWT `sub`)다. 판정 시각·`projectId`·`buildId`·`contentVersion`은 본문으로 받지 않는다.
- 200은 Review 확정만 뜻한다(Studio·Catalog 반영 아님).
- 운영 API(ADMIN, `stove.messaging.ops.enabled`로 켬): 지금 `GET /api/v1/ops/dlt`·`POST /api/v1/ops/dlt/replay`·`GET /api/v1/ops/outbox/dead`·`POST /api/v1/ops/outbox/dead/{id}/requeue`. 목표: 실패 보관 표 키 단위 재투입·키 단위 `DEAD` 회수·재발행(결정 155·160·161, 경로는 common messaging 설계).
- 별도 접수·재접수·이의 제기·판정 수정 API는 없다.

## 이벤트

### 소비 — 그룹 `review-project`(`ReceiveProjectReview.CONSUMER_GROUP`), 토픽 `stove.studio.v1`, 기본 컨테이너 팩토리

| 이벤트(플랫폼 행) | 리스너 → Mapper | Mapper 검증 | 서비스 | 업무 멱등 키 | 실패 처리 |
|---|---|---|---|---|---|
| `ProjectReviewRequested` ([design 리터럴 표](../platform/design.md#eventtype에-없는-현행-이벤트-문자열-리터럴-10개-t26), 흐름 1 S1) | `StudioEventListener.receive` → `ReviewSubmissionMapper.from` → `ReviewSubmission` | 봉투 `eventId`·`eventType` = 본문, 키 = `snapshot.productCode`, 본문 `snapshot` 있음. `ReviewSubmission` 생성자: ID 양수, 문자열 길이, `productCode` 형식, `currency`, 빌드 ID·크기·SHA-256 64자, `storagePath` 필수, 장르 1~3개 | `ReceiveProjectReview.execute` | PK `review_request_id`(같은 스냅샷이면 무변화, 다르면 충돌) | L1·E2·E3 영구 실패, 그 밖은 공통 재시도 |
| `ProjectReviewWithdrawalRequested` (같은 표, S2) | 같은 리스너 → `ReviewWithdrawalMapper.from` → `ReviewWithdrawalInput` | 봉투 = 본문, 키 = `request.productCode`, `ReviewWithdrawalValues.requireEvent`·`requireInstruction`(ID 양수·형식·사유 1~2000자 또는 없음) | `ReceiveReviewWithdrawal.execute` | PK `withdrawal_id` + `requireSame` | L1·E6·E7 영구 실패 |

- 목표: 리스너는 `EventType` 상수로 타입을 고르고(T26), (`eventType`, `schemaVersion`)으로 Mapper를 고르며(결정 170), 모르는 타입은 건너뜀 지표를 낸다(T31). 이 리스너는 토픽 하나만 받으므로 (토픽, 타입) 짝 대조(P2.3-8)는 토픽 고정으로 충족한다.
- 영구 실패 분류는 공통 예외 하나(T20)로 던진다. 지금은 `BusinessException(INVALID_REQUEST/CONFLICT)`·`DataIntegrityViolationException`이 모두 4회 재시도된다(RV-T5).

### 발행 — 토픽 `stove.review.v1`, 키·`aggregateId` = `productCode`, `aggregateType` = `"ProjectReview"`

| 이벤트(플랫폼 행) | 발행 지점(같은 트랜잭션) | 본문 칸 출처 | 상태 |
|---|---|---|---|
| `ProjectReviewApproved` ([리터럴 표](../platform/design.md#eventtype에-없는-현행-이벤트-문자열-리터럴-10개-t26), S3·S3a) | `DecideProjectReview.execute`(`decide`가 true일 때만) | `reviewRequestId`·`projectId`·`contentVersion`·`productCode` = `project_review`, `actor` = JWT `sub`. 목표: `snapshot`(저장 `ProjectSnapshot` 전체, R9.1) 추가, `schemaVersion` 2([결정 181](../../../docs/decisions.md#approval-snapshot-payload)) | 현행(리터럴) → 칸 추가 목표 |
| `ProjectReviewRejected` (같은 표, S3) | 같음 | 위 + `feedback` = 확정 사유 | 현행(리터럴) |
| `ProjectReviewWithdrawn` ([목표 행](../platform/design.md#목표-행-아직-코드에-없음), SP-7 ①) | `ReceiveReviewWithdrawal`(E4 `REQUESTED`·`WITHDRAWN`), `ReceiveProjectReview`(E1 미해결 철회) | `withdrawalId`, `reviewRequestId`, `projectId`, `contentVersion`, `productCode` = 철회 명령·행, `withdrawnBy`·`withdrawalReason`·`withdrawnAt` = `project_review`, `requestEventId` = `project_review.request_event_id` | 목표(결정 176, T21) |
| `ProjectReviewWithdrawalDenied` (목표 행, SP-7 ②) | `ReceiveReviewWithdrawal`(E4 `APPROVED`·`REJECTED`) | `withdrawalId`·`reviewRequestId`·`projectId`·`contentVersion`·`productCode`·`targetStatus`(enum {`APPROVED`,`REJECTED`})·`requestEventId`. `actor`·`feedback` 없음 | 목표(결정 176, T21) |
| `ProjectReviewWithdrawalResolved` | 지금 위 두 자리(`ReviewWithdrawal.resolve`, `review:core/domain/ReviewWithdrawal.java:56-69`) | `status` 문자열이 `WITHDRAWN`/`APPROVED`/`REJECTED`, `actor`·`feedback`·`finalizedAt`이 철회자/판정자를 번갈아 담음 | 현행 → 목표 분리안 SP-7. P2.7-4 순서로 제거 |

- 이벤트 UUID·`occurredAt`은 payload `of()` 팩터리가 만든다. 목표: 본문 `occurredAt` = 헤더 `occurredAt` = 사실 시각(판정은 `decidedAt`, 철회는 `withdrawnAt`), `schemaVersion` 헤더(T25).
- 결과 이벤트 한 건 = Studio 명령·요청 하나의 응답. 정방향 응답 쌍(design ["정방향 응답 쌍"](../platform/design.md#정방향-응답-쌍-결정-158))에서 Review는 Studio 요청의 응답자이고, 자기가 쥔 미응답 기록은 원요청 없는 철회뿐이다(R10.1).
- 옛 상수 5개(`ReviewApproved`·`ReviewRejected`·`SubmissionReviewApproved`·`ReviewChangesRequested`·`ReviewAppealed`)는 내지 않는다. 제거는 플랫폼 T30, Review는 근거만 낸다(RV-T9).

## 모듈 안 불변식

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| RV-I1 | 한 요청의 최종 결과는 하나이고 바뀌지 않는다 | `ProjectReview.decide`(`status != REQUESTED`면 같은 판정만 false, 그 밖 409, `ProjectReview.java:99-101`), `ProjectReview.withdraw`(`:114`), `ProjectReviewRepository.lock` | `ProjectReviewTest#rejectionCannotBeReopenedOrHaveItsReasonChanged`, `#withdrawalCannotReplaceADecisionOrBeRequestedByAnotherCreator`, `ProjectReviewIntegrationTest#simultaneousOppositeDecisionsCommitExactlyOneResult` |
| RV-I2 | 스냅샷과 연결 값은 최초 값 그대로다 | `@Column(updatable=false)`(`ProjectReview.java:30-47`), `ReceiveProjectReview.java:37-45` 동등 비교 | `ProjectReviewIntegrationTest#duplicateReceptionPreservesTheSnapshotAndIndependentRerequests` |
| RV-I3 | Project별 `REQUESTED` ≤ 1 (플랫폼 RI-7의 Review 쪽) | `uk_review_active_project`(V11 생성 열) | `ReviewWithdrawalMigrationIntegrationTest#preservesHistoryAndOnlyOneActiveReviewCanExistAcrossVersions`, `#refusesConflictingLegacyActiveReviewsWithoutDeletingEitherRequest` |
| RV-I4 | `status`는 네 값뿐이다 | 목표 `chk_project_review_status`(지금 Java enum만) | 없음 → tasks MC-R1 |
| RV-I5 | 상태별 칸: 판정 칸 ⇔ `APPROVED`/`REJECTED`, `feedback` ⇔ `REJECTED`, 철회 칸 ⇔ `WITHDRAWN` | 목표 `chk_project_review_decision`·`_feedback`·`_withdrawn`(지금 `decide`·`withdraw` 코드만) | 없음 → tasks RV-T1 |
| RV-I6 | 판정·결과 Outbox는 함께 커밋되거나 함께 없다 | `DecideProjectReview` `@Transactional` + `OutboxRecorder`(`MANDATORY`) | `ProjectReviewIntegrationTest#outboxFailureRollsBackTheDecision` |
| RV-I7 | 철회 보관·Inbox·해결·결과 Outbox는 함께 커밋된다 | `ReceiveReviewWithdrawal`·`ReceiveProjectReview` `@Transactional` | `ReviewWithdrawalIntegrationTest#outboxFailureRollsBackWithdrawalInboxAndItsReceipt` |
| RV-I8 | 판정과 철회가 겹치면 먼저 커밋된 쪽이 최종이고 결과 이벤트가 그 결과와 같다 | 게이트 → 행 잠금(`ReceiveReviewWithdrawal.java:31-43`), `DecideProjectReview.java:24` | `ReviewWithdrawalIntegrationTest#simultaneousApprovalAndWithdrawalAgreeOnOneFinalState`, `#approvalBeforeWithdrawalReturnsTheFirstFinalFact`, `ReviewWithdrawalTest#aDecisionThatWonTheRaceIsReturnedWithItsOriginalActorAndTime` |
| RV-I9 | 원요청 없이 심사 행을 만들지 않고, 철회된 요청은 원요청 재전달로 다시 열리지 않는다 | `ReceiveReviewWithdrawal.java:43-44`, `ReceiveProjectReview.java:37-45·51-55` | `ReviewWithdrawalIntegrationTest#withdrawalBeforeOriginalIsDurableAndOriginalReplayCannotReopenIt`, `#realKafkaCanDeliverWithdrawalBeforeOriginalAndRepeatedOriginalRemainsWithdrawn` |
| RV-I10 | `actor`는 검증된 JWT `sub`다 | `ReviewController`(`@CurrentSubject`), `RejectProjectReviewRequest` 칸 하나 + 모르는 칸 400 | `ReviewAccessTest#authorizedDecisionUsesTheJwtSubject`, `#rejectionCannotSupplyAnActorOrOtherDecisionFields` |
| RV-I11 | 같은 `withdrawalId`의 명령 내용은 바뀌지 않는다 | `ReviewWithdrawal.requireSame` | `ReviewWithdrawalTest#anotherTargetAndConflictingInstructionCannotReplaceTheOriginalCommand`, `ReviewWithdrawalIntegrationTest#wrongOwnerAndChangedBusinessCommandDoNotMarkInboxOrOverwriteState` |
| RV-I12 | 한 `reviewRequestId`의 판정 이벤트는 많아야 하나다(재시도는 새 이벤트 없음) | `decide`가 재시도에 false → `DecideProjectReview.java:25` | `ProjectReviewIntegrationTest#decisionRetriesPublishOnceAndRetainTheFirstActor`, `ProjectReviewTest#identicalDecisionRetryKeepsOriginalActorAndTime` |
| RV-I13 | 철회 명령 하나에 결과 이벤트는 하나이고, 그 종류(`Withdrawn`/`WithdrawalDenied`)는 해결 시점 `project_review.status`로 정해진다 | 목표: 해결 코드가 `resolved`를 `false → true`로 바꿀 때만 발행(지금 `ReviewWithdrawal.resolve`, 한 이벤트 타입) | 없음 → tasks RV-T2 |
| RV-I14 | 결과 이벤트의 연결 값은 저장된 행 값이다 | `DecideProjectReview.java:28-40`(행 getter), `ReviewWithdrawal.resolve`(대상 대조 뒤 명령 값) | 단언하는 테스트 확인 못 함 → tasks RV-T3 |

모듈을 넘는 정합: RI-1(Studio 최종 = Review 상태), RI-2(Catalog 공개본 ⇒ Review `APPROVED`·같은 스냅샷 — Review는 R9로 대조 재료를 낸다), RI-7, RI-8([design 정합 조건](../platform/design.md#끝-상태와-정합-조건-ri-1--ri-9)).

## 외부 연동과 adapter 메모

| 포트(core.port) | adapter | 계약 |
|---|---|---|
| `ReviewBuildStorage.download(snapshot)` → `BuildDownload(url, expiresAt, fileSize, checksum)` | `S3ReviewBuildStorage` | 스냅샷 경로가 설정 버킷의 객체인지 확인 → 메타 조회로 크기 = `fileSize` 확인 → 읽기 URL 서명. 경로 밖·없음·크기 불일치 = 충돌(409), 저장소 일시 장애 = 503. 업로드·삭제·버킷 생성 없음 |

adapter 메모(공급자 규칙, core 계약 아님):

- 경로 형식은 `s3://<bucket>/<key>`이고 adapter가 접두사 `s3://` + 설정 버킷을 확인한다(`S3ReviewBuildStorage.java:35-40`). 목표: 이 형식 확인은 adapter에만 둔다. 지금 core `ReviewSubmission`이 `storagePath.startsWith("s3://")`를 검사한다(`review:core/domain/ReviewSubmission.java:23`, RV-T6).
- `HeadObject` 404 → 409, 그 밖의 `S3Exception`·`SdkClientException` → 503(`:63-69`). HEAD만으로 SHA-256 검증을 주장하지 않는다. 심사자가 받은 파일을 `checksum`과 대조할 수 있다.
- 엔드포인트가 있으면 path-style을 켜고, 서명 URL 주소는 `presign-endpoint`로 따로 줄 수 있다(외부 접근 주소). Studio와 같은 버킷·리전·암호화 조건, Studio 구현 클래스는 참조하지 않는다.
- 읽기 전용 계정에는 같은 버킷의 GetObject·ListBucket만 준다. Studio 쓰기 계정으로 대체하지 않는다(`ReviewStoragePropertiesTest#genericWriterCredentialsCannotSubstituteForTheReviewAccount`).
- ZIP 실행·자동 품질 검사·등급 판정을 하지 않는다.

## 설정

| 키(환경 변수) | 기본값 | 비고 |
|---|---|---|
| `stove.review-storage.endpoint` (`STORAGE_ENDPOINT`) | `http://localhost:9000` | 지정 시 path-style |
| `stove.review-storage.presign-endpoint` (`STORAGE_PRESIGN_ENDPOINT`) | endpoint와 같음 | 서명 URL 외부 주소 |
| `stove.review-storage.region` (`STORAGE_REGION`) | `us-east-1` | |
| `stove.review-storage.bucket` (`STORAGE_BUCKET`) | `stove-builds` | 비면 기동 실패 |
| `stove.review-storage.access-key`·`secret-key` (`REVIEW_STORAGE_ACCESS_KEY`·`_SECRET_KEY`) | 없음(필수) | 비면 기동 실패 |
| `stove.review-storage.presign-ttl` (`REVIEW_BUILD_URL_TTL`) | `PT5M` | 1초~15분 밖이면 기동 실패 |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri`·`jwk-set-uri` (`JWT_ISSUER_URI`·`JWT_JWK_SET_URI`) | `http://localhost:8091`… | Auth `AUTH_ISSUER`와 일치 |
| `stove.messaging.ops.enabled` | `false` | 운영 API 켜기 |
| `stove.outbox.*` | relay on, batch 200, poll 200ms, max-retry 10 | 공통 |
| `server.port` | 8086 | Gateway `REVIEW_URI` |

- 바인딩은 `config`의 `ReviewStorageProperties`가 하고 adapter에는 값·클라이언트만 넘긴다(AGENTS.md 3절).
- 운영/테스트 차이: 테스트 전용 경로·설정 키는 없다. 다만 저장소 엔드포인트·버킷·리전·JWT 주소·DB 계정(`DB_PASSWORD:stove1234`)의 로컬 개발 기본값이 운영 설정에 들어 있어, 운영에서 값을 빠뜨려도 기동한다(RV-T10, 원칙 ⑦ 확인 필요).
- 심사자 계정은 Auth 기동 전에 `AUTH_REVIEWER_EMAIL`·`AUTH_REVIEWER_PASSWORD`(ADMIN은 `AUTH_ADMIN_*`)로 준비한다. 공개 가입은 REVIEWER를 주지 않는다.
- 검증: `./gradlew :apps:review:test :apps:review:integrationTest`, `python3 tools/verification/verify-review.py`(앱 포트 +30000, `REVIEW_TEST_PORT_OFFSET`, MinIO 이미지 `STOVE_TEST_MINIO_IMAGE`).
