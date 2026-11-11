# 설계: Studio

> 상태: 작성 중(결정 150·178). 이 문서는 **목표 계약**이다. 코드가 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"(MC-ST*·ST-T*)에 있다.
> 경로 줄임: `studio:` = `apps/studio/src/main/java/com/stove/studio/`, `studio/db/Vn` = `apps/studio/src/main/resources/db/migration/Vn__*.sql`. 줄 번호는 `docs/spec-foundation` 작업 폴더 기준.

## 아키텍처 위치

- 포트 8085, 스키마 `stove_studio`(MySQL), 객체 저장소 버킷 하나. Gateway(8080)가 `/api/v1/studio/**`를 라우팅한다. Studio는 다른 업무 모듈을 동기 호출하지 않는다([플랫폼 design "동기 의존"](../platform/design.md#동기-의존-http): Gateway → Studio만).
- 패키지 경계는 [P1.1](../platform/requirements.md#p11-경계-코드-수준)과 AGENTS.md 3절을 따른다.

| 층 | Studio의 내용 |
|---|---|
| `core.domain` | `Project`·`StorePage`·장르 값(`ProjectGenreValues`), `Build`, `ProjectReviewRequest`, `ProjectReleaseRequest`, `ProjectRequestCommand`, 상태 enum(목표: `ReviewRequestStatus`·`ReleaseRequestStatus`·`CommandState`·`ProjectRequestKind`·`DeniedTargetStatus`), 내부 입력(`SubmissionCommand`·`WithdrawalCommand`·결과 입력) |
| `core.port` | `BuildStorage`(객체 저장·크기 조회·삭제·썸네일 읽기) |
| `core.service` | `CreateProject`·`UpdateStorePage`·`RegisterBuild`·`UploadThumbnail`·`GetThumbnail`·`ReadProjects`, `RequestProjectReview`·`RequestProjectRelease`·`RequestProjectWithdrawal`, `RecordProjectReviewResult`·`RecordProjectReleaseResult`·`RecordProjectWithdrawalResult`, (목표) `RecordProjectReleaseClosure`, (목표) 정방향 대기 게이지 |
| `api.controller` | `ProjectController`·`BuildController`·`AssetController`·`ProjectWithdrawalController`·`GenreController` |
| `api.listener` | `ProjectResultListener`(그룹 `studio-project`), `ProjectResultMapper`·`ProjectWithdrawalResultMapper`(목표: 철회 결과 4종·운영자 종결을 내부 입력으로) |
| `adapter` | `adapter.storage.S3BuildStorage` |
| `config` | `SecurityConfig`, `StorageConfiguration`·`ObjectStorageProperties`, `StudioUploadProperties`·`StudioAssetProperties`, `StudioUseCaseConfiguration`, `RequestJsonConfig` |

- core가 받는 결과 입력은 Studio 내부 record다. 공통 계약 payload(`com.stove.common.kafka.contract.payload.*`)는 `api.listener`의 `*Mapper.from`과 Outbox 기록(서비스)에서만 쓰고, 도메인 모델은 payload 타입을 입력·출력으로 갖지 않는다(AGENTS.md 3절, 지금 차이는 ST-T5).
- 서비스가 Inbox(`ProcessedEventGuard`)·Project 행 잠금·업무 변경·Outbox(`OutboxRecorder`)를 한 트랜잭션에서 조율한다.

## 데이터 모델

목표 스키마다. 지금과 다른 칸·제약은 "지금" 열과 tasks 번호로 표시한다.

### `studio_project` — Project 작업본

| 칸 | 타입·제약 | 뜻 | 지금 |
|---|---|---|---|
| `id` | BIGINT PK | Project ID. 이벤트의 `projectId` | 같음 |
| `product_code` | VARCHAR(50) NOT NULL, `uk_studio_project_code` | 상품 코드. 모든 Studio 이벤트의 파티션 키 | 같음 |
| `owner_subject` | VARCHAR(100) NOT NULL | Auth `sub`. 바뀌지 않음 | 같음 |
| `title`·`short_description`·`thumbnail_url`·`video_url`·`content` | 200·500·1000·1000·LONGTEXT | 작업본 상점 정보 | 같음 |
| `price` | BIGINT NOT NULL, `chk_studio_project_price (price >= 0)` | KRW 정수 가격 | CHECK 없음(DTO `@PositiveOrZero`만, ST-T6) |
| `current_build_id` | BIGINT NULL, FK (`id`, `current_build_id`) → `studio_build(project_id, id)` | 대표 빌드 | FK 없음(ST-T6) |
| `content_version` | BIGINT NOT NULL, `chk_studio_project_version (content_version >= 1)` | 작업본 버전 | CHECK 없음(ST-T6) |
| `current_review_request_id`·`current_release_request_id` | BIGINT NULL | 가장 최근에 만든 요청(또는 영수증이 가리킨 요청). `releaseEligible`·현재 버전 요청 조회용 참조이며 상태가 아니다 | 같음 |
| `entity_version` | BIGINT | JPA 낙관 버전 | 같음 |

장르: `studio_project_genre(project_id, genre_code)` PK, FK `ON DELETE CASCADE`(V27). 코드 값 검증은 `GenreCatalog`(코드 정의, 결정 71)가 한다 — DB에 코드 목록 CHECK를 두지 않는 것은 선택 중지 코드도 과거 행에 남아야 하기 때문이다(결정 73).

### `studio_build` — 성공한 전체 ZIP

| 칸 | 타입·제약 | 지금 |
|---|---|---|
| `id` PK, `project_id` FK → `studio_project` | | 같음 |
| `version` VARCHAR(100), `uk_studio_build_version(project_id, version)` | Project 안 빌드 버전 유일 | 같음 |
| `file_name` 255, `storage_path` 1000 | 저장소 경로(고유) | 같음 |
| `file_size` BIGINT, `chk_studio_build_size (file_size > 0)` | | CHECK 없음(ST-T6) |
| `checksum` CHAR(64), `chk_studio_build_checksum (checksum REGEXP '^[0-9a-f]{64}$')` | SHA-256 hex | CHECK 없음(ST-T6) |
| (목표) UK `(project_id, id)` | `studio_project.current_build_id` 복합 FK 대상 | 없음 |

빌드 행은 성공한 업로드만 만들고 바꾸거나 지우지 않는다. 상태 칸이 없다.

### `studio_review_request` — 심사 요청

| 칸 | 타입·제약 | 뜻 | 지금 |
|---|---|---|---|
| `id` PK | | `reviewRequestId` | 같음 |
| `project_id` FK, `content_version`, `build_id` | (목표) FK `build_id` → `studio_build(id)` | 요청한 작업본 버전·빌드 | `build_id` FK 없음(ST-T6) |
| `snapshot` LONGTEXT NOT NULL | 불변 `ProjectSnapshot` JSON | 같음 |
| `status` VARCHAR(20) NOT NULL, `chk_studio_review_request_status IN ('REQUESTED','APPROVED','REJECTED','WITHDRAWN')` | `ReviewRequestStatus` | CHECK 없음, Java `String`(MC-ST3) |
| `decided_by` VARCHAR(100) NULL | 판정자(Review `actor`) | 칸 이름 `actor`, 철회 때 철회자도 담음(ST-T4) |
| `feedback` VARCHAR(2000) NULL | 반려 사유 | 같음 |
| `withdrawn_by` VARCHAR(100) NULL | 철회 행위자(`ProjectReviewWithdrawn.withdrawnBy`) | 없음 — `actor`에 섞임(ST-T4) |
| `withdrawal_reason` VARCHAR(2000) NULL | 철회 사유 | 같음 |
| `request_event_id` VARCHAR(64) NOT NULL | 이 요청의 `ProjectReviewRequested.eventId` | NULL 허용(V23 이행 행, ST-T7) |
| `result_received_at` DATETIME(6) NULL | Studio가 결과를 기록한 Studio 시각 | 같음 |
| `finalized_at` DATETIME(6) NULL | 결과를 낸 모듈의 확정 시각. 값으로만 저장 | 같음 |
| `active_project_id` 생성 열 `CASE WHEN status='REQUESTED' THEN project_id END`, `uk_studio_active_review` | 활성 심사 1건 | 같음(V26) |
| (목표) UK `(id, project_id, content_version)` | 출시 요청 복합 FK 대상 | 없음 |

칸 조합 CHECK(목표, ST-T4): `chk_studio_review_withdrawn ((status='WITHDRAWN') = (withdrawn_by IS NOT NULL))`, `chk_studio_review_decided (decided_by IS NULL OR status IN ('APPROVED','REJECTED'))`, `chk_studio_review_feedback (feedback IS NULL OR status='REJECTED')`, `chk_studio_review_result ((status='REQUESTED') = (result_received_at IS NULL))`.
`REJECTED`인데 `feedback`이 NULL인 행은 철회 거절 결과가 판정 결과보다 먼저 온 경우에만 생긴다(아래 상태 모델 E5, 결정 176 — notes E-1 소급 후보).

### `studio_release_request` — 출시 요청

| 칸 | 타입·제약 | 뜻 | 지금 |
|---|---|---|---|
| `id` PK | | `releaseRequestId` | 같음 |
| `project_id`, `review_request_id`, `content_version` | (목표) 복합 FK (`review_request_id`, `project_id`, `content_version`) → `studio_review_request(id, project_id, content_version)` | 근거 심사 요청과 같은 Project·버전 | FK는 `review_request_id`만, `project_id` FK 없음(ST-T6) |
| `status` VARCHAR(20), `chk_studio_release_request_status IN ('REQUESTED','COMPLETED','REJECTED','WITHDRAWN','CLOSED')` | `ReleaseRequestStatus` | CHECK 없음, `CLOSED` 없음(MC-ST3, ST-T2) |
| `reject_reason` VARCHAR(40) NULL, CHECK 값은 Catalog `ReleaseRejectReason` 목록 | Catalog 거절 사유 enum | 칸 이름 `failure_reason` VARCHAR(2000) 자유 문자열(ST-T9) |
| `withdrawn_by`·`withdrawal_reason` | 철회 행위자·사유 | 같음 |
| `closed_by` VARCHAR(100)·`close_reason` VARCHAR(2000) NULL | 운영자 종결 근거(값) | 없음(ST-T2) |
| `request_event_id` VARCHAR(64) NOT NULL | `ProjectReleaseRequested.eventId` | NULL 허용(V23 이행 행, ST-T7) |
| `result_received_at`·`finalized_at` | 리뷰 요청과 같은 뜻 | 같음 |
| `active_project_id` 생성 열, `uk_studio_active_release` | 활성 출시 1건 | 같음(V26) |

칸 조합 CHECK(목표): `(status='WITHDRAWN') = (withdrawn_by IS NOT NULL)`, `(status='CLOSED') = (closed_by IS NOT NULL)`, `reject_reason IS NULL OR status='REJECTED'`, `(status='REQUESTED') = (result_received_at IS NULL)`.
출시 요청은 스냅샷을 따로 저장하지 않고 `review_request_id`의 스냅샷을 그대로 이벤트에 싣는다(정본 하나, 원칙 ③).

### `studio_request_command` — 명령 영수증

| 칸 | 타입·제약 | 뜻 | 지금 |
|---|---|---|---|
| `id` PK | | 철회 명령이면 이벤트의 `withdrawalId` | 같음 |
| `project_id` FK, `subject`, `content_version`, `target_request_id` | | 명령자·대상 요청·대상 버전 | 같음 |
| `request_key` VARCHAR(64) `utf8mb4_bin`, `uk_studio_command(project_id, kind, request_key)` | 창작자 `requestId` | 같음 |
| `kind` VARCHAR(32), `chk_studio_command_kind IN ('REQUEST_REVIEW','REQUEST_RELEASE','WITHDRAW_REVIEW','WITHDRAW_RELEASE')` | `ProjectRequestKind` | Java enum만, CHECK 없음(MC-ST3) |
| `reason` VARCHAR(2000) NULL | 철회 사유(선택) | 같음 |
| `state` VARCHAR(20), `chk_studio_command_state` | `CommandState` {`ACCEPTED`, `PENDING`, `WITHDRAWN`, `DENIED`}, 종류별 허용 값은 아래 | CHECK 없음, Java `String`(MC-ST3) |
| `denied_target_status` VARCHAR(20) NULL | `DENIED`일 때 대상의 최종 상태(`DeniedTargetStatus`) | 없음 — `final_status`가 `WITHDRAWN`과 대상 상태를 겸함, `final_actor`·`final_feedback`이 철회자/판정자·철회 사유/반려 사유를 겸함(ST-T1) |
| `finalized_at` DATETIME(6) NULL | 결과 모듈의 확정 시각(값) | 같음 |
| `target_request_event_id` VARCHAR(64) NULL | 결과가 가리킨 대상 원요청 이벤트 ID(대조 근거) | 칸 이름 `source_request_event_id` |
| (목표) `pending_target` 생성 열 `CASE WHEN state='PENDING' THEN CONCAT(kind, ':', target_request_id) END`, `uk_studio_command_pending_target` | 대상당 `PENDING` 철회 1건 | 없음 — 코드 검사만(ST-T3) |

종류·상태 CHECK(목표): `(kind IN ('REQUEST_REVIEW','REQUEST_RELEASE') AND state='ACCEPTED') OR (kind IN ('WITHDRAW_REVIEW','WITHDRAW_RELEASE') AND state IN ('PENDING','WITHDRAWN','DENIED'))`, `(state IN ('WITHDRAWN','DENIED')) = (finalized_at IS NOT NULL)`, `(state='DENIED') = (denied_target_status IS NOT NULL)`, `denied_target_status IS NULL OR (kind='WITHDRAW_REVIEW' AND denied_target_status IN ('APPROVED','REJECTED')) OR (kind='WITHDRAW_RELEASE' AND denied_target_status IN ('COMPLETED','REJECTED','CLOSED'))`.
`denied_target_status`의 `CLOSED`는 운영자가 종결한 출시에 온 철회의 거절에 쓴다([결정 184](../../../docs/decisions.md#withdrawal-denied-closed)).

### 공통 표

- `outbox_event`(V2·V3), `processed_event`(Inbox, `uk_processed_event(event_id, consumer_group)`).
- (목표) 소비 실패 보관·키 정지 표(결정 155·160), `outbox_republish`(결정 161) — 공통 메시징 이행 파일로 둔다(ST-T10).
- V1~V22의 옛 표(`game_project`·`workspace`·`game_build`·`submission`·`game_release` 등)는 이행 이력으로만 남고 런타임 코드가 읽지 않는다(V24 주석). 지울지는 범위 밖.
- Flyway V1~V27 체크섬을 바꾸지 않는다. 목표 제약은 새 이행 파일(V28 이후)로 더하며, 적용 전 위반 행을 세는 조회를 이행 테스트에 둔다(V26 전환 절과 같은 방식).

## 상태 모델

트리거 기호(모든 표 공통). 응답 코드: 400 `INVALID_REQUEST`, 403 `FORBIDDEN`, 404 `NOT_FOUND`, 409 `CONFLICT`. "영구 실패" = 소비 경로의 계약 위반으로 재시도 없이 실패 보관·경보([P2.6-3](../platform/requirements.md#p26-재시도dlt)), 오프셋은 넘어가고 기존 기록은 그대로다.

| 기호 | 종류 | 트리거 |
|---|---|---|
| H1 | HTTP | `POST /projects` |
| H2 | HTTP | `PUT /projects/{id}/store-page`(기대 `contentVersion`) |
| H3 | HTTP | `POST /projects/{id}/builds` |
| H4 | HTTP | `POST /projects/{id}/thumbnail` |
| H5 · H5r · H5x | HTTP | `POST …/review-requests` — 새 `requestId` · 같은 `requestId` 같은 내용 · 같은 `requestId` 다른 내용 |
| H6 · H6r · H6x | HTTP | `POST …/release-requests` — 같은 세 경우 |
| H7 · H7r · H7x | HTTP | `POST …/review-requests/{t}/withdrawals` — 같은 세 경우 |
| H8 · H8r · H8x | HTTP | `POST …/release-requests/{t}/withdrawals` — 같은 세 경우 |
| H9 | HTTP | 모든 GET(상태 변화 없음) |
| E1 · E2 | 소비 | `ProjectReviewApproved` · `ProjectReviewRejected` |
| E3 | 소비 | `ProjectReviewWithdrawn` (이행 중: `ProjectReviewWithdrawalResolved(status=WITHDRAWN)`) |
| E4 · E5 | 소비 | `ProjectReviewWithdrawalDenied(targetStatus=APPROVED · REJECTED)` (이행 중: `…Resolved(status=APPROVED·REJECTED)`) |
| E6 · E7 · E8 | 소비 | `ProjectReleaseCompleted` · `ProjectReleaseRejected` · `ProjectReleaseClosed`(가칭) |
| E9 | 소비 | `ProjectReleaseWithdrawn` (이행 중: `ProjectReleaseWithdrawalResolved(status=WITHDRAWN)`) |
| E10 · E11 · E12 | 소비 | `ProjectReleaseWithdrawalDenied(targetStatus=COMPLETED · REJECTED · CLOSED)` (E12는 [결정 184](../../../docs/decisions.md#withdrawal-denied-closed)) |
| EX | 소비 | 위 이벤트 가운데 식별자가 맞지 않는 것: 대상 요청·명령 없음, `projectId`·`contentVersion`·`productCode` 불일치, 출시 결과의 `requestEventId` ≠ 원요청 이벤트 ID, 철회 결과의 `withdrawalId`·종류·행위자 불일치, (토픽, `eventType`) 짝 불일치, 봉투·본문 계약 위반 |
| J1 | 예약 작업 | 정방향 대기 게이지 집계(R10, 읽기 전용) |
| O1 | 운영자 | ADMIN Outbox 재발행·`DEAD` 회수(같은 `eventId`) |
| O2 | 운영자 | ADMIN 실패 보관 표 재투입 → 같은 리스너 경로이므로 그 레코드의 E열과 같다 |

Studio에는 업무 상태를 직접 바꾸는 운영자 명령이 없다(운영자 종결은 Catalog가 하고 Studio는 E8로 받는다).

### Project

상태 enum이 없다(공개 상태를 두지 않는다, R9.3). 행의 있음/없음과 `contentVersion`만 있다.

| 상태 \ 트리거 | H1 | H2 | H3 | H4 | H5·H6 | H7·H8 | H9 | E1~E12·EX | J1·O1·O2 |
|---|---|---|---|---|---|---|---|---|---|
| 없음 | 허용 → 있음(`contentVersion=1`). 같은 `productCode`가 있으면 거절 409 | 거절 404 | 거절 404 | 거절 404 | 거절 404 | 거절 404 | 거절 404 | 영구 실패(EX) | 무변화 |
| 있음 | 거절 409(코드 중복) | 소유자 아님 403 · 기대 버전 불일치 409 · 같은 값 무변화 · 다른 값 허용 → `contentVersion+1` | 소유자 아님 403 · 기대 버전 불일치 409 · 빌드 버전 중복 409 · 형식·크기 위반 400·413 · 성공 허용 → 대표 빌드 교체, `contentVersion+1` | 소유자 아님 403 · 형식·크기 위반 400·413 · 읽은 뒤 버전이 바뀜 409 · 성공 허용 → `thumbnail_url` 교체, `contentVersion+1` | 아래 판정표. 새 요청이 생기거나 영수증이 요청을 가리키면 `current_*_request_id`만 갱신(버전 무변화) | 무변화(Project 잠금만) | 무변화 | 무변화(Project 잠금만) | 무변화 |

### Build

| 상태 \ 트리거 | H3 | 그 밖의 모든 트리거 |
|---|---|---|
| 없음 | 허용 → 있음(Project 판정이 허용일 때) | 무변화 |
| 있음 | 같은 `(project_id, version)` 거절 409 · 다른 버전은 새 행 | 무변화(불변) |

### 새 요청 판정표 (H5·H6, Project 잠금 안에서 위에서부터)

| 순서 | 조건 | 심사(H5) | 출시(H6) |
|---|---|---|---|
| 1 | Project 없음 / 소유자 아님 | 404 / 403 | 404 / 403 |
| 2 | 같은 (종류, `requestId`) 영수증 있음 | H5r: 영수증의 요청 반환 · H5x: 409 | H6r · H6x 같음 |
| 3 | 기대 `contentVersion` ≠ 현재 | 409 | 409 |
| 4 | (출시만) 현재 버전 심사 요청이 `APPROVED` 아님 | — | 409 |
| 5 | 활성 요청이 다른 버전 | 409 | 409 |
| 6 | 활성 요청이 현재 버전이고 `PENDING` 철회 명령 있음 | 409 | 409 |
| 7 | 활성 요청이 현재 버전이고 철회 명령 없음 | 새 요청 없이 영수증(`ACCEPTED`) → 활성 요청, 202(R5.8) | 같음 |
| 8 | 현재 버전 요청이 `APPROVED`(심사) / `COMPLETED`(출시) | 새 요청 없이 영수증 → 그 요청, 202(R3.9) | 같음(R4.9) |
| 9 | (심사만) 작업본 미준비·장르 위반 | 409 | — |
| 10 | 대표 빌드(심사) / 스냅샷 빌드(출시) 객체 크기 ≠ `fileSize` | 409 | 409 |
| 11 | 그 밖(현재 버전 요청이 없음·`REJECTED`·`WITHDRAWN`·(출시)`CLOSED`) | 허용: 요청 `REQUESTED` + 영수증 + `ProjectReviewRequested` Outbox, 202 | 허용: 요청 `REQUESTED` + 영수증 + `ProjectReleaseRequested` Outbox, 202 |

### 심사 요청 `ReviewRequestStatus`

| 값 | 뜻 |
|---|---|
| `REQUESTED` | Studio가 요청을 냈고 Review의 최종 사실을 아직 받지 않았다. 활성 자리를 차지한다 |
| `APPROVED` | Review가 이 스냅샷을 승인했다(E1, 또는 E4가 먼저 옴) |
| `REJECTED` | Review가 반려했다(E2, 또는 E5가 먼저 옴) |
| `WITHDRAWN` | 창작자 철회가 Review에서 판정보다 먼저 커밋됐다(E3) |

| 상태 \ 트리거 | H5(새 `requestId`) | H5r · H5x | H7 | H7r · H7x | E1 | E2 | E3 | E4 | E5 | EX | H2·H3·H4·H6·H8·E6~E12 | J1·O1 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 없음 | 판정표 11이면 허용 → `REQUESTED` | 쌍 없음: 영수증과 행은 한 트랜잭션에 생긴다 → 무변화 | 거절 404 | 쌍 없음 → 무변화 | 영구 실패(대상 없음) | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 무변화 |
| `REQUESTED` | 같은 버전·철회 없음: 무변화(영수증만, 판정표 7) · 그 밖 거절 409(판정표 5·6) | 무변화(같은 요청 반환) · 거절 409 | 철회 명령 없으면 허용: 행 무변화, 명령 `PENDING` 생성 · `PENDING` 있으면 거절 409 | 무변화(기존 영수증) · 거절 409 | 허용 → `APPROVED`(`decided_by`, `finalized_at`), 자리 해제 | 허용 → `REJECTED`(`feedback`·`decided_by`), 자리 해제 | 허용 → `WITHDRAWN`(`withdrawn_by`·`withdrawal_reason`), 자리 해제. 같은 트랜잭션에서 명령 `WITHDRAWN` | 허용 → `APPROVED`(`decided_by` NULL, E1이 채움), 자리 해제. 명령 `DENIED` | 허용 → `REJECTED`(`feedback` NULL, E2가 채움), 자리 해제. 명령 `DENIED` | 영구 실패 | 무변화(스냅샷 불변) | 무변화 |
| `APPROVED` | 현재 버전이면 무변화(영수증만, 판정표 8) · 과거 버전이면 무변화(이 행과 무관) | 무변화 · 거절 409 | 거절 409(이미 최종) | 무변화 · 거절 409 | 무변화. `decided_by`가 NULL이면 채움(E4가 먼저 온 경우) | 영구 실패(모순, MC-ST2) | 영구 실패 | 무변화 | 영구 실패 | 영구 실패 | 무변화(H6은 이 행을 읽기만) | 무변화 |
| `REJECTED` | 무변화(이 행과 무관, 판정표 11로 새 행) | 무변화 · 거절 409 | 거절 409 | 무변화 · 거절 409 | 영구 실패(모순, MC-ST2) | 같은 사유면 무변화, `feedback` NULL이면 채움 · 다른 사유면 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 영구 실패 | 무변화 | 무변화 |
| `WITHDRAWN` | 무변화(이 행과 무관, 새 행 — 결정 59) | 무변화 · 거절 409 | 거절 409 | 무변화 · 거절 409 | 영구 실패(MC-ST2) | 영구 실패(MC-ST2) | 같은 `withdrawalId`면 무변화 · 다르면 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 무변화 |

### 출시 요청 `ReleaseRequestStatus`

| 값 | 뜻 |
|---|---|
| `REQUESTED` | Studio가 출시를 요청했고 Catalog의 최종 사실을 아직 받지 않았다. 활성 자리를 차지한다 |
| `COMPLETED` | Catalog가 이 요청을 공개로 확정했다(최초 공개·교체·같은 승인본 재출시 모두, 결정 58) |
| `REJECTED` | Catalog가 거절했다(`reject_reason`, 예: `STALE_CONTENT_VERSION`·승인 불일치) |
| `WITHDRAWN` | 창작자 철회가 Catalog 공개 확정보다 먼저 커밋됐다 |
| `CLOSED` | ADMIN이 Catalog에서 정지(`BLOCKED`)된 공개 작업을 공개 없이 종결했다(결정 168). `REJECTED`와 다른 사실이다(원칙 ①) |

| 상태 \ 트리거 | H6(새 `requestId`) | H6r · H6x | H8 | H8r · H8x | E6 | E7 | E8 | E9 | E10 | E11 | E12 | EX | 그 밖(H2~H5·H7·E1~E5) | J1·O1 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 없음 | 판정표 11이면 허용 → `REQUESTED` | 쌍 없음 → 무변화 | 거절 404 | 쌍 없음 → 무변화 | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 무변화 |
| `REQUESTED` | 같은 버전·철회 없음: 무변화(영수증만) · 그 밖 거절 409 | 무변화 · 거절 409 | 철회 명령 없으면 허용: 행 무변화, 명령 `PENDING` · 있으면 거절 409 | 무변화 · 거절 409 | 허용 → `COMPLETED`, 자리 해제 | 허용 → `REJECTED`(`reject_reason`), 자리 해제 | 허용 → `CLOSED`(`closed_by`·`close_reason`), 자리 해제(R7.7) | 허용 → `WITHDRAWN`(`withdrawn_by`·`withdrawal_reason`), 자리 해제, 명령 `WITHDRAWN` | 허용 → `COMPLETED`, 자리 해제, 명령 `DENIED` | 허용 → `REJECTED`(`reject_reason` NULL, E7이 채움), 자리 해제, 명령 `DENIED` | 허용 → `CLOSED`(`closed_by`는 E8이 채움. 같은 그룹·토픽·키라 E8이 먼저 오므로 방어 칸, [결정 184](../../../docs/decisions.md#withdrawal-denied-closed)), 자리 해제, 명령 `DENIED` | 영구 실패 | 무변화 | 무변화 |
| `COMPLETED` | 현재 버전이면 무변화(영수증만, 판정표 8) · 과거 버전이면 무변화 | 무변화 · 거절 409 | 거절 409(이미 최종) | 무변화 · 거절 409 | 무변화 | 영구 실패(MC-ST2) | 영구 실패 | 영구 실패 | 무변화 | 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 무변화 |
| `REJECTED` | 무변화(새 행 — R4.8) | 무변화 · 거절 409 | 거절 409 | 무변화 · 거절 409 | 영구 실패(MC-ST2) | 같은 사유 무변화, NULL이면 채움 · 다른 사유 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 영구 실패 | 영구 실패 | 무변화 | 무변화 |
| `WITHDRAWN` | 무변화(새 행 — 결정 58) | 무변화 · 거절 409 | 거절 409 | 무변화 · 거절 409 | 영구 실패(MC-ST2, **1회차 지적 B3, 판정 전** — 철회된 요청이 늦은 승인으로 공개되면 여기서 드러난다) | 영구 실패 | 영구 실패 | 같은 `withdrawalId` 무변화 · 다르면 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 무변화 |
| `CLOSED` | 무변화(새 행 — 결정 168) | 무변화 · 거절 409 | 거절 409 | 무변화 · 거절 409 | 영구 실패 | 영구 실패 | 무변화 | 영구 실패 | 영구 실패 | 영구 실패 | 무변화 | 영구 실패 | 무변화 | 무변화 |

E8 행은 플랫폼 흐름 1의 S6b다(단계 목록에 S6b가 빠진 것은 **1회차 지적 B8, 판정 전**).

### 철회 명령 `CommandState` (종류 `WITHDRAW_REVIEW`·`WITHDRAW_RELEASE`)

| 값 | 뜻 |
|---|---|
| `PENDING` | 접수했고 Outbox에 철회 요청을 적었다. 대상 모듈의 결과를 기다린다 |
| `WITHDRAWN` | 대상 모듈에서 철회가 이겼다 |
| `DENIED` | 대상이 먼저 최종이 되어 철회가 거절됐다(`denied_target_status`) |

| 상태 \ 트리거 | H7·H8(새 `requestId`) | H7r·H8r | H7x·H8x | 철회됨(E3·E9, 이 명령의 `withdrawalId`) | 철회 거절(E4·E5·E10·E11·E12, 이 명령) | 대상의 판정·출시 결과(E1·E2·E6·E7·E8) | EX(종류·대상·행위자 불일치, 같은 결과 `eventId`가 다른 명령에 이미 쓰임) | 그 밖·J1·O1 |
|---|---|---|---|---|---|---|---|---|
| 없음 | 대상 `REQUESTED`이고 `PENDING` 없음: 허용 → `PENDING` + 철회 요청 Outbox, 202 · 대상 최종 409 · 대상 없음·다른 Project 404 · `PENDING` 있음 409 | 쌍 없음 → 무변화 | 쌍 없음 → 무변화 | 영구 실패(명령 없음) | 영구 실패 | 무변화 | 영구 실패 | 무변화 |
| `PENDING` | 이 명령과 무관(다른 `requestId`는 같은 대상이면 409) | 무변화(기존 영수증, 202) | 거절 409 | 허용 → `WITHDRAWN`(`finalized_at`), 대상도 `WITHDRAWN` | 허용 → `DENIED`(`denied_target_status`, `finalized_at`). 대상이 `REQUESTED`면 그 상태로 채우고, 이미 최종이면 같은 값이어야 한다(다르면 영구 실패) | 무변화(명령은 철회 결과로만 끝난다. 대상은 최종이 되어 자리가 풀린다) | 영구 실패 | 무변화 |
| `WITHDRAWN` | 무관 | 무변화 | 거절 409 | 같은 결과면 무변화 | 영구 실패 | 무변화(대상 행 표가 판정) | 영구 실패 | 무변화 |
| `DENIED` | 무관 | 무변화 | 거절 409 | 영구 실패 | 같은 `targetStatus`면 무변화 · 다르면 영구 실패 | 무변화 | 영구 실패 | 무변화 |

### 제출 명령 `CommandState` (종류 `REQUEST_REVIEW`·`REQUEST_RELEASE`)

| 상태 \ 트리거 | H5·H6 새 `requestId` | H5r·H6r | H5x·H6x | 그 밖 모든 트리거 |
|---|---|---|---|---|
| 없음 | 판정표 7·8·11이면 허용 → `ACCEPTED`(대상 요청 ID) · 판정표 1~6·9·10이면 영수증 없이 거절 | 쌍 없음 → 무변화 | 쌍 없음 → 무변화 | 무변화 |
| `ACCEPTED` | 이 영수증과 무관 | 무변화(같은 요청 반환, 202) | 거절 409 | 무변화(영수증은 결과를 갖지 않는다 — 결과는 요청 행이 정본) |

## API 계약

`/api/v1/studio` 아래. 응답 봉투는 공통 `ApiResponse`. 공개 썸네일 외에는 CREATOR와 소유권을 확인한다(R9). 요청 본문 검증 실패는 400, 업로드 상한 초과는 413.

| 메서드 · 경로 | 역할 | 요청 | 응답 | 오류 | 멱등 |
|---|---|---|---|---|---|
| GET `/genres` | CREATOR | — | 코드·표시명·선택 가능 | — | 조회 |
| POST `/projects` | CREATOR | `productCode`(`[A-Za-z0-9][A-Za-z0-9._-]{0,49}`)·`title`(≤200) | 201 Project | 400·409(코드 중복) | `productCode` 유일 |
| GET `/projects` | CREATOR | `page`·`size` | 페이지(R8) | 400 | 조회 |
| GET `/projects/{id}` | CREATOR·소유자 | — | 작업본·`contentVersion`·현재 버전 요청·`releaseEligible`·활성 요청 | 403·404 | 조회 |
| PUT `/projects/{id}/store-page` | CREATOR·소유자 | `contentVersion`·상점 정보·`genres`(≤3) | 200 Project | 400·403·404·409(버전) | 기대 버전 |
| POST `/projects/{id}/builds` | CREATOR·소유자 | multipart `version`·`file`(ZIP) | 201 Build | 400·403·404·409·413 | `(project, version)` 유일 |
| GET `/projects/{id}/builds` | CREATOR·소유자 | `page`·`size` | 페이지 | 400·403·404 | 조회 |
| POST `/projects/{id}/thumbnail` | CREATOR·소유자 | multipart PNG/JPEG | 201 자산 ID·URL | 400·403·404·409·413 | 없음(새 자산) |
| GET `/assets/{projectId}/{assetId}` | 공개 | — | 이미지 | 404 | 조회 |
| POST `/projects/{id}/review-requests` | CREATOR·소유자 | `requestId`·`contentVersion` | 202 심사 요청 | 400·403·404·409 | `(project, REQUEST_REVIEW, requestId)` |
| GET `/projects/{id}/review-requests` | CREATOR·소유자 | `page`·`size` | 페이지 | 400·403·404 | 조회 |
| POST `/projects/{id}/release-requests` | CREATOR·소유자 | `requestId`·`contentVersion` | 202 출시 요청 | 400·403·404·409 | `(project, REQUEST_RELEASE, requestId)` |
| GET `/projects/{id}/release-requests` | CREATOR·소유자 | `page`·`size` | 페이지 | 400·403·404 | 조회 |
| POST `/projects/{id}/review-requests/{t}/withdrawals` | CREATOR·소유자 | `requestId`·`reason?`(≤2000) | 202 영수증, `no-store` | 400·403·404·409 | `(project, WITHDRAW_REVIEW, requestId)` |
| GET `/projects/{id}/review-requests/{t}/withdrawals[/{w}]` | CREATOR·소유자 | — | 영수증 배열(≤100) / 영수증, `no-store` | 403·404 | 조회 |
| POST·GET `/projects/{id}/release-requests/{t}/withdrawals[/{w}]` | 같음 | 같음 | 같음 | 같음 | `(project, WITHDRAW_RELEASE, requestId)` |

- 요청·영수증 응답은 API 전용 DTO다. JPA 엔티티를 그대로 직렬화하지 않고 스냅샷 원문·`request_event_id`를 내보내지 않는다(지금 차이 ST-T11).
- 철회 영수증 응답(목표): `id`·`kind`·`targetRequestId`·`state`·`reason`·`deniedTargetStatus`(DENIED일 때)·`finalizedAt`·`createdAt`. 판정자·반려 사유·Catalog 거절 사유는 대상 요청 응답(`decidedBy`·`feedback`·`rejectReason`)으로 본다(R6.2, 결정 176). 지금 응답의 `finalStatus`·`finalActor`·`finalFeedback`은 없앤다(ST-T1, 결정 64의 "병행 호환 경로 없이 전환"을 따른다).
- 심사 판정·공개 확정·`/publish`를 수행하는 API는 없다. 운영 API는 공통 `/api/v1/ops/**`(ADMIN, `stove.messaging.ops.enabled`)뿐이다.

## 이벤트

이벤트의 뜻·키·순서·소비자는 플랫폼 스펙이 정본이다. 여기는 Studio가 어떻게 지키는지만 적는다.

### 발행 (모두 `stove.studio.v1`, 키 `productCode`, `aggregateType="Project"`)

| 이벤트 | 플랫폼 행 | 발행 지점(같은 트랜잭션) | Studio 쪽 보장 |
|---|---|---|---|
| `ProjectReviewRequested` | [이벤트 목록(리터럴)](../platform/design.md#eventtype에-없는-현행-이벤트-문자열-리터럴-10개-t26), 흐름 1 S1 | `RequestProjectReview.execute`(`studio:core/service/review/RequestProjectReview.java:66-77`) — 심사 요청 행·영수증·Outbox | 요청 행의 `request_event_id` = 이벤트 `eventId`. 스냅샷은 요청 행 `snapshot`과 같은 값 |
| `ProjectReleaseRequested` | 같음, S4 | `RequestProjectRelease.execute`(`…/release/RequestProjectRelease.java:58-67`) | 스냅샷 = `review_request_id` 행의 스냅샷(ST-I4). `request_event_id` 저장 |
| `ProjectReviewWithdrawalRequested` | 같음, S2 | `RequestProjectWithdrawal.execute`(`…/project/RequestProjectWithdrawal.java:64-69`) | `withdrawalId` = 명령 ID, `requestId` = 대상 요청 ID, `contentVersion` = 대상 버전 |
| `ProjectReleaseWithdrawalRequested` | 같음, S5 | 같음(`:70-71`) | 같음 |

- 상수: 4종 모두 `EventType` 상수로 올린다([T26](../platform/tasks.md)). 헤더 `schemaVersion`은 공통 릴레이가 싣는다(결정 170).
- 재발행: 4종 모두 ADMIN 재발행 대상이다(R11.1, 결정 161, ST-T10).

### 소비 (그룹 `studio-project`, 토픽 `stove.review.v1`·`stove.release.v1`, 리스너 `ProjectResultListener`)

| 이벤트(토픽) | 플랫폼 행 | `*Mapper.from` 검증 | 서비스 · 전이 | 업무 멱등 키(2층) |
|---|---|---|---|---|
| `ProjectReviewApproved`·`ProjectReviewRejected` (review) | 이벤트 목록(리터럴), S3 | 봉투 `eventId`·`eventType` = 본문, 키 = `productCode`, ID·버전 양수, 거절이면 `feedback` 1~2000자, 승인이면 `feedback` 없음, `actor` 1~100자 | `RecordProjectReviewResult`: Inbox → Project 잠금 → 요청 행 잠금 → 식별자(ID·`projectId`·`contentVersion`·`productCode`) 대조 → 심사 요청 표 E1·E2 | 심사 요청 상태(최종이면 같은 결과만 무변화). Inbox `uk_processed_event` |
| `ProjectReviewWithdrawn`·`ProjectReviewWithdrawalDenied` (review) | [목표 행](../platform/design.md#목표-행-아직-코드에-없음) SP-7, S2 | 위 봉투 규칙 + `withdrawalId`·`reviewRequestId`·`projectId`·`contentVersion`·`requestEventId`, Denied는 `targetStatus` ∈ {`APPROVED`,`REJECTED`} | `RecordProjectWithdrawalResult`: Inbox → Project 잠금 → 명령 → 대상 요청 잠금 → 철회 명령 표·심사 요청 표 E3~E5 | 명령 `withdrawalId` + 상태 |
| `ProjectReleaseCompleted`·`ProjectReleaseRejected` (release) | 이벤트 목록(리터럴), S7 | 봉투 규칙, `requestEventId` 1~64자, 거절이면 `reason` ∈ Catalog `ReleaseRejectReason` | `RecordProjectReleaseResult`: Inbox → Project 잠금 → 요청 행 잠금 → 식별자 + `requestEventId` 대조 → 출시 요청 표 E6·E7 | 출시 요청 상태 |
| `ProjectReleaseClosed`(가칭) (release) | 목표 행, S6b | 봉투 규칙, `releaseRequestId`·`projectId`·`contentVersion`·`productCode`·`requestEventId`·운영자·사유 | (목표) `RecordProjectReleaseClosure` → 출시 요청 표 E8 | 출시 요청 상태 |
| `ProjectReleaseWithdrawn`·`ProjectReleaseWithdrawalDenied` (release) | 목표 행 SP-8, S5 | Denied `targetStatus` ∈ {`COMPLETED`,`REJECTED`,`CLOSED`}([결정 184](../../../docs/decisions.md#withdrawal-denied-closed)) | `RecordProjectWithdrawalResult` → 철회 명령 표·출시 요청 표 E9~E12 | 명령 `withdrawalId` + 상태 |
| (이행 중) `ProjectReviewWithdrawalResolved`·`ProjectReleaseWithdrawalResolved` | 현행 → SP-7·SP-8 | `status` 문자열을 Withdrawn/Denied 입력으로 바꾼다. `actor`·`feedback`은 Withdrawn일 때만 철회자·사유로 쓴다 | 같음 | 같음 |

- (토픽, `eventType`) 짝: review 토픽은 심사 결과 4종(+이행 1종), release 토픽은 출시 결과 5종(+이행 1종)만 받는다. 다른 짝은 영구 실패(R7.8, MC-ST1). 모르는 `eventType`은 건너뛰고 지표로 센다([P2.3-7](../platform/requirements.md#p23-소비--inbox와-업무-멱등), T31).
- 실패 분류: DB 잠금·연결 실패는 재시도 가능. 매퍼 계약 위반, 대상 요청·명령 없음(Studio는 요청 행과 요청 이벤트를 한 트랜잭션에 쓰므로 "아직 없음"이 될 수 없다), 식별자 불일치, 최종 상태와 모순되는 결과는 영구 실패다(공통 영구 실패 예외, [T20](../platform/tasks.md), ST-T8).
- 순서: 두 토픽 사이 순서는 보장되지 않는다([P2.5-2](../platform/requirements.md#p25-순서-보장-범위)). 위 상태 표는 같은 요청에 대한 결과 사실이 어느 순서로 와도 같은 최종 상태로 수렴하도록 짰다 — 판정(E1·E2) ↔ 철회 거절(E4·E5), 출시 결과(E6·E7·E8) ↔ 철회 거절(E10~E12)은 어느 쪽이 먼저든 같은 상태가 되고, 먼저 온 쪽이 비워 둔 값(판정자·사유)은 뒤에 온 판정 사실이 채운다(플랫폼 실패 표 S2·S7 "순서 역전").
- 결과의 `finalized_at`·`occurredAt`은 값으로만 저장한다. 같은 결과의 재전달 판정은 `withdrawalId`·대상 ID·상태·`targetStatus`로 하고 시각 값의 같음으로 하지 않는다(원칙 ⑥, 지금 `ProjectRequestCommand.java:113`이 `finalizedAt`을 비교 — ST-T1에서 함께 바꾼다).

### 정방향 응답 쌍에서 Studio가 세는 것 ([플랫폼 design](../platform/design.md#정방향-응답-쌍-결정-158), R10)

| 세는 기록 | 응답 사실 | 게이지(목표 이름, 가칭) |
|---|---|---|
| 출시 요청 `REQUESTED` | E6·E7·E8·E9·E10~E12 | `studio_forward_pending{pair="release"}` 건수·최장 대기 초 |
| 철회 명령 `PENDING`(종류별) | E3~E5 / E9~E12 | `studio_forward_pending{pair="review_withdrawal"\|"release_withdrawal"}` |
| 심사 요청 `REQUESTED` | E1~E5 | `studio_forward_pending{pair="review"}` — 사람 판정 대기와 전달 누락을 구별하지 못함 [결정 필요 M-ST-3] |

대기 시간은 Studio 행의 `created_at`(Studio 시계)부터 잰다. 역방향 위반(Studio `COMPLETED`인데 Catalog 기록 없음 등, [P3.9-3](../platform/requirements.md#p39-출시-흐름의-모듈-간-정합))은 Studio 스키마만으로는 볼 수 없으므로 실험·여정 검사기(`tools/verification/`)의 몫이다.

## 트랜잭션과 잠금

- 모든 명령·결과 처리는 Project 행 비관 잠금(`ProjectRepository.lock`, `PESSIMISTIC_WRITE`)을 먼저 잡고, 그다음 대상 요청 행을 잠근다(요청 → Project 순서는 쓰지 않는다). 같은 Project의 심사·출시·철회·결과 기록이 이 잠금으로 직렬화된다.
- 활성 자리는 요청 상태에서 생성되는 열(`active_project_id`)과 유일 키가 지킨다. 결과 기록이 상태를 최종으로 바꾸면 자리는 같은 커밋에서 풀린다. 별도 해제 칸·플래그를 두지 않는다(원칙 ④).
- 업로드: 임시 디스크 검사 → 객체 저장(트랜잭션 밖) → 짧은 트랜잭션(잠금·소유권·기대 버전 재확인·행 기록) → 실패 시 새 객체 삭제.
- 저장소 크기 확인(HEAD): 목표는 [P1.2-4](../platform/requirements.md#p12-동기-의존-방향)("동기 호출은 DB 트랜잭션 밖")와 [P3.4-1](../platform/requirements.md#p34-출시-요청철회-studio--catalog)("Project 잠금 안에서 HEAD 확인")이 함께 성립하는 방식이다. 두 문장이 지금 서로 어긋나 플랫폼 판정을 기다린다(notes F2). 그 전까지는 지금처럼 잠금 안에서 확인한다(`RequestProjectReview.java:64`, `RequestProjectRelease.java:59-60`, `UpdateStorePage.java:29`, ST-T12).

## 모듈 안 불변식

모듈을 넘는 정합은 플랫폼 RI 번호를 링크한다: RI-1(심사 결과 일치), RI-2(공개본 = 승인 스냅샷 — Studio 쪽 ST-I3·ST-I4, 가드는 Catalog), RI-4(출시 결과 일치), RI-7(활성 자리 — Studio 쪽 ST-I1·ST-I2), RI-8(철회 명령 `WITHDRAWN`이면 대상도 `WITHDRAWN` — ST-I9).

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| ST-I1 | Project마다 `REQUESTED` 심사 요청 ≤ 1 | `uk_studio_active_review`(V26) + Project 잠금(`RequestProjectReview.java:40`) | `StudioWithdrawalMigrationIntegrationTest#preservesRequestHistoryAndEnforcesOneActiveReviewAcrossVersions`, `ProjectWithdrawalIntegrationTest#twoConcurrentNewCommandsShareOneActiveReviewAndOneRequestEvent` |
| ST-I2 | Project마다 `REQUESTED` 출시 요청 ≤ 1 | `uk_studio_active_release`(V26) + Project 잠금 | `ProjectWithdrawalIntegrationTest#concurrentReleaseCommandsCreateOneRequestAndOneEvent`, `#anEarlierReleaseBlocksNewVersionsUntilItsWithdrawalIsFinalWithoutBlockingReview` |
| ST-I3 | 출시 요청은 같은 Project·같은 `contentVersion`의 `APPROVED` 심사 요청을 가리킨다 | 코드: `RequestProjectRelease.java:45-47`. DB(목표): 복합 FK (`review_request_id`, `project_id`, `content_version`). 승인 상태는 DB로 못 지켜 코드 가드 | `ProjectReviewRequestTest#externalApprovalOnlySatisfiesTheReviewedMetadataVersion`. 복합 FK 위반 INSERT 실패 → 없음 → tasks ST-T6 |
| ST-I4 | 출시 요청 이벤트의 스냅샷 = 근거 심사 요청의 스냅샷 | 출시 요청이 스냅샷을 따로 갖지 않고 심사 요청 행에서 읽는다(`RequestProjectRelease.java:58`) | `ProjectRequestIntegrationTest#metadataAndImmutableRequestsAreIndependentOfExternalReviewAndReleaseExecution` |
| ST-I5 | 요청 스냅샷은 바뀌지 않는다 | `ProjectReviewRequest`에 스냅샷 변경 메서드 없음 | `ProjectTest#outgoingRequestsDoNotFreezeMetadataOrCreateAnApprovalOrPublicationState`, `ProjectRequestIntegrationTest#metadataCanChangeWhileAReviewRequestIsPendingWithoutReusingItsLaterApproval` |
| ST-I6 | 최종 상태 요청은 다른 최종 상태로 바뀌지 않고, 모순 결과는 드러난다 | 목표: `ProjectReviewRequest.recordResult`·`ProjectReleaseRequest.recordResult`가 `recordResolution`처럼 다른 결과에 영구 실패 | 지금 `ProjectReviewRequestTest#contradictoryLateResultsDoNotOverwriteTheRecordedRejection`은 조용히 무시를 단언 → MC-ST2에서 영구 실패 단언으로 바꾼다 |
| ST-I7 | (Project, 종류, `requestId`)마다 명령 1건, 같은 키는 같은 내용 | `uk_studio_command` + `ProjectRequestCommand.requireSubmission`·`requireWithdrawal` | `ProjectRequestCommandTest#oldSubmissionKeysKeepTheirOriginalVersionAndTarget`, `#optionalReasonAndAWithdrawalReceiptAreStableAcrossRetries` |
| ST-I8 | 대상 요청마다 `PENDING` 철회 명령 ≤ 1 | 지금: 코드 검사(`RequestProjectWithdrawal.java:52-53`)만. 목표: `uk_studio_command_pending_target` | 없음 → tasks ST-T3 |
| ST-I9 | 철회 명령 `WITHDRAWN` ⇔ 대상 요청 `WITHDRAWN`(같은 트랜잭션) | `RecordProjectWithdrawalResult.execute` | `ProjectWithdrawalIntegrationTest#withdrawalWaitsForFinalResultThenSameContentCanBeSubmittedWithoutRevivingOldCommands` |
| ST-I10 | 결과는 식별자가 모두 맞는 요청·명령에만 기록 | `RecordProject*Result`, `ProjectRequestCommand.resolve` | `ProjectWithdrawalIntegrationTest#aResultWithTheWrongOriginOrReusedEnvelopeCannotFinalizeAnotherCommand`, `ProjectResultMapperTest#requiresMatchingIdTypeAndProductKey`. `productCode` 불일치·출시 `requestEventId` 불일치 → 없음 → MC-ST2·ST-T8 |
| ST-I11 | `contentVersion` ≥ 1, 실제 변경 때만 1 증가 | `Project.changed`·`update`·`selectBuild`, 목표 CHECK | `ProjectGenreTest#aGenreOnlyEditChangesTheVersionButReorderingDoesNot`, `ProjectTest#projectOwnerAndEditVersionProtectMetadata` |
| ST-I12 | 가격 ≥ 0 정수 | DTO `@PositiveOrZero`, 목표 도메인 검사 + `chk_studio_project_price` | `ProjectAccessTest#fractionalPriceIsRejectedInsteadOfSilentlyTruncated`. 음수 → 없음 → ST-T6 |
| ST-I13 | 대표 빌드는 같은 Project의 빌드다 | 코드: `RegisterBuild`·`RequestProjectReview.java:60-63`. 목표: 복합 FK | `ProjectRequestIntegrationTest#anotherOwnerAndFailedFileCannotReplaceCurrentBuild` |
| ST-I14 | 업로드 실패는 새 객체를 남기지 않고 기존 객체를 건드리지 않는다 | `RegisterBuild.java:67-75`, `UploadThumbnail` 같은 구조 | `ProjectRequestIntegrationTest#anotherOwnerAndFailedFileCannotReplaceCurrentBuild`, `UploadFilesTest#rejectsEmptyAndOversizedStream` |
| ST-I15 | 상태 칸은 enum 값만 갖는다 | 목표 CHECK 5개(요청 2·명령 kind·state·`denied_target_status`) | 없음 → MC-ST3 |
| ST-I16 | 요청 행과 요청 이벤트는 함께 생긴다(`request_event_id` NOT NULL) | 한 트랜잭션 + `OutboxRecorder`(`MANDATORY`) | `ProjectWithdrawalIntegrationTest#outboxFailureDoesNotLeaveAWithdrawalCommandAndWrongOwnerCannotSubmitIt`(철회 명령). 요청 행 NOT NULL → 없음 → ST-T7 |

## 외부 연동과 adapter 메모

- `core.port.BuildStorage`: `put(projectId, file, extension, contentType) → path`, `size(path)`, `delete(path)`, `thumbnailDownload(projectId, assetId)`. core는 저장소 종류를 모른다.
- `adapter.storage.S3BuildStorage`(S3 API): 로컬·검증은 MinIO, 운영은 S3를 가정한다. `endpoint`가 있으면 경로 스타일 주소, `encryption-enabled`면 SSE 헤더, `presign-endpoint`는 외부 주소용. 버킷 정책이 태그(`stove-download-blocked`)로 다운로드 읽기를 막는 것은 Catalog·Download의 일이고 Studio는 태그를 쓰지 않는다.
- 객체 경로는 Project별 고유 이름이고 같은 경로를 덮어쓰지 않는다. 저장소 객체의 불변 보존은 운영 조건이다([플랫폼 실패 표 S4 "상대 상태가 이미 바뀜"](../platform/design.md#단계별-실패-표)).

## 설정

| 키 | 기본값 | 뜻 | 운영·테스트 차이 |
|---|---|---|---|
| `stove.upload.max-artifact-bytes` | 1073741824 | 빌드 상한 | 같음. 올리면 `spring.servlet.multipart.max-file-size`·`max-request-size`도 맞춘다 |
| `stove.asset.max-thumbnail-bytes` | 5242880 | 썸네일 상한 | 같음 |
| `stove.asset.public-base-url` | `http://localhost:8080` | 공개 썸네일 URL 앞부분 | 운영은 환경 변수로 준다. 로컬 기본값이 운영 계약에 남는다(ST-T13) |
| `stove.storage.*`(`endpoint`·`presign-endpoint`·`bucket`·`access-key`·`secret-key`·`encryption-enabled`) | 로컬 MinIO 값 | adapter 조립 | 자격 증명 기본값(`stove`/`stove1234`)이 운영 설정에 있다(ST-T13) |
| `stove.messaging.ops.enabled` | `false` | 공통 DLT·DEAD 운영 API | 목표: 재투입·재발행 경로를 운영에서 쓸 수 있어야 한다(결정 160·161, ST-T10) |
| `stove.outbox.*` | relay on, 200건, 200ms, 최대 10회 | 공통 릴레이 | 같음 |
| `spring.kafka.listener.ack-mode` | `record` | P2.3-6 | 같음 |

테스트 전용 경로·플래그는 운영 계약에 없다(원칙 ⑦). 예외로 남은 로컬 기본값은 위 ST-T13이다.
