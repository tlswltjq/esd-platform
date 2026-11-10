# 설계: Catalog

> 상태: 작성 중(결정 150·178). 요구사항은 [requirements.md](requirements.md)(`R11.5` = R11의 5번 수용 기준), 코드와 다른 곳은 [tasks.md](tasks.md)(`MC-C*`·`CA-T*`). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/design.md)을 링크한다. 경로 줄임: `catalog:` = `apps/catalog/src/main/java/com/stove/catalog/`, `catalog/db/Vn` = `apps/catalog/src/main/resources/db/migration/Vn__*.sql`. 줄 번호는 2026-11-10 `spec-foundation` 작업 폴더 기준.

## 아키텍처 위치

- 포트 8081, 저장소 MySQL 스키마 `stove_catalog`만 읽고 쓴다([플랫폼 P1.1-2](../platform/requirements.md)).
- 패키지: `core/{domain, port, service}` + `api/{controller, listener, scheduler}` + `adapter/{storage, trace}` + `config`. 외부 이벤트는 `api.listener`의 `*Mapper.from`에서 내부 입력으로 바꾸고, 서비스가 Inbox·잠금·상태 전이·Outbox를 한 트랜잭션으로 조율한다(AGENTS.md 3절). ArchUnit `CatalogArchitectureTest`가 경계를 지킨다.
- 동기 의존: Catalog는 다른 업무 모듈을 동기 호출하지 않는다. 받는 쪽으로서 Order·Store·Settlement·Download의 내부 조회 4개 경로를 연다([플랫폼 design "동기 의존"](../platform/design.md#동기-의존-http)). 외부 공급자는 객체 저장소 하나(태그 쓰기·읽기 확인)이며 `core.port.BuildDistribution` 뒤에 있다.
- 비동기 의존: 쌍 S2(Studio 출시 요청·철회 → Catalog → 출시 결과)의 응답 쪽, 쌍 S1의 Review 승인 사실을 받는 두 번째 소비자(결정 167), Store 공개 사본의 단방향 발행자([플랫폼 design "비동기 의존"](../platform/design.md#비동기-의존-요청--사실-응답-쌍-p13-2)).
- 정본: 공개본·가격·판매/노출 상태·소유자, 다운로드 제한([플랫폼 design "정본 소유"](../platform/design.md#정본-소유)). 출시 상세·스냅샷의 정본은 Studio, 심사 판정의 정본은 Review이며 Catalog는 둘을 불변 사본으로 보관한다.
- **승인은 Review 사실로 확인하고, 생산 권한도 좁힌다.** 지금 설계의 "Studio 명령을 신뢰"를 결정 167로 바꾼다: 공개를 확정하기 전에 Review가 낸 `ProjectReviewApproved`의 보관 사본과 출시 요청을 값으로 대조한다. `stove.studio.v1`·`stove.review.v1` 생산 권한은 각 신뢰 서비스로 한정하는 운영 조건을 유지한다. Catalog는 빌드 파일을 다시 검사하거나 다운로드 URL을 발급하지 않는다.

## 데이터 모델

목표 표·칸·제약이다. 지금 이행 파일과의 차이는 tasks "코드와 맞출 것"에 있다.

| 테이블 | 칸(요약) | 제약(목표) |
|---|---|---|
| `catalog_product` | `id`(내부 `productId`)·`project_id`·`product_code`·`owner_subject`·`current_release_request_id`·`published_at`(최초 공개)·`updated_at`·`sale_status`·`platform_restricted`·`listing_preference`·`platform_listing_hidden`·`catalog_version`·`download_restricted`·`download_restriction_version`·`download_storage_status`·`download_storage_checked_at`·`download_storage_next_attempt_at` | `uk_catalog_project(project_id)`·`uk_catalog_product_code(product_code)`, `fk_catalog_current_release`, `ck_catalog_sale_status IN ('ON_SALE','STOPPED')`, `ck_catalog_version > 0`, `ck_platform_restricted IN (0,1)`(목표 추가), `ck_listing_preference`, `ck_platform_listing_hidden`, `chk_download_storage_status IN ('NOT_REQUIRED','PENDING','APPLIED','FAILED')`(목표 `NOT_REQUIRED` 추가), `chk_download_not_required ((download_storage_status='NOT_REQUIRED') = (download_restriction_version=1))`(목표, 지금 `chk_download_work` 대체), `chk_download_work (download_storage_status='NOT_REQUIRED' OR download_storage_next_attempt_at IS NOT NULL)`, `chk_download_restricted_tracked (download_restricted=FALSE OR download_storage_status<>'NOT_REQUIRED')`(목표), `chk_download_version >= 1` |
| `catalog_release` | `release_request_id`(PK)·`request_event_id`·`review_request_id`·`project_id`·`product_code`·`content_version`·`snapshot`(원본 `ProjectSnapshot` JSON)·`status`·`reason`·`requested_at`(Studio 사실 시각, 값으로만)·`processed_at`(Catalog 확정 시각)·`withdrawn_by`·`withdrawal_reason` | PK = 출시 요청당 결과 하나, `uk_catalog_request_event(request_event_id)`, `idx_catalog_release_version(project_id, content_version)`, 목표: `chk_catalog_release_status IN ('COMPLETED','REJECTED','WITHDRAWN','CLOSED')`, `chk_catalog_release_reason (reason IS NULL OR reason IN ('STALE_CONTENT_VERSION','APPROVAL_MISMATCH'))`, `chk_catalog_release_rejected ((status='REJECTED') = (reason IS NOT NULL))`, `chk_catalog_release_withdrawn ((status='WITHDRAWN') = (withdrawn_by IS NOT NULL))` |
| `catalog_publication_job` | `request_id`(PK)·`source_event_id`·`occurred_at`·`review_request_id`·`project_id`·`content_version`·`product_code`·`snapshot`·`state`·`next_attempt_at`·`attempt_count`·`last_error_code`(목표, 분류 enum)·`last_error`(요약 500자)·`blocked_at`(목표)·`trace_parent`·`created_at`·`updated_at` | `uk_catalog_publication_event(source_event_id)`, `idx_catalog_publication_due(state, next_attempt_at, request_id)`, `idx_catalog_publication_version`, 목표: `chk_publication_job_state IN ('AWAITING_APPROVAL','PENDING','BLOCKED','COMPLETED')`, `chk_publication_job_due ((state='PENDING') = (next_attempt_at IS NOT NULL))`, `chk_publication_job_blocked ((state='BLOCKED') = (blocked_at IS NOT NULL))`, `idx_publication_job_review(review_request_id, state)` |
| `catalog_request_gate` | `request_id`(PK) | 원요청보다 먼저 온 철회에도 잠글 대상이 있게 하는 잠금 행 |
| `catalog_review_gate`(목표) | `review_request_id`(PK) | 승인과 출시 접수를 같은 `reviewRequestId`로 직렬화하는 잠금 행(승인·출시 중 먼저 온 쪽이 만든다) |
| `catalog_review_approval`(목표) | `review_request_id`(PK)·`approval_event_id`·`project_id`·`content_version`·`product_code`·`snapshot`(M-CA-4)·`approved_at`(Review 사실 시각, 값으로만)·`received_at` | `uk_catalog_review_approval_event(approval_event_id)`, 갱신 없음(INSERT만) |
| `catalog_release_withdrawal` | `withdrawal_id`(PK)·`request_id`·`project_id`·`content_version`·`product_code`·`owner_subject`·`reason`·`requested_at`(Studio 사실 시각)·`resolved`·`created_at`·`updated_at` | `idx_catalog_withdrawal_pending(request_id, resolved, withdrawal_id)`. `resolved`는 두 값의 명시적 상태다(module-carryover 공통 표). M-CA-3이 A안이면 `state IN ('RECEIVED','RESOLVED','CONFLICTED')` enum으로 바꾼다 |
| `catalog_release_closure`(목표) | `request_id`(PK, FK `catalog_release`)·`closed_by`·`reason`·`block_code`·`closed_at` | `CHAR_LENGTH(TRIM(reason)) > 0`, 출시 결과 `CLOSED`와 같은 트랜잭션 |
| `catalog_publication_job_action`(목표) | `id`·`request_id`·`action`·`actor_subject`·`reason`·`from_state`·`acted_at` | `action IN ('RESUME','CLOSE')`, `reason` 공백 불가. 운영 이력, 갱신 없음 |
| `catalog_sale_change` | 판매 조건 전후·`previous_version`·`catalog_version`·`actor_subject`·`authority`·`changed_at` | `uk_catalog_sale_change(product_id, catalog_version)`, `ck_catalog_sale_change_version (catalog_version = previous_version + 1)`, `ck_catalog_sale_change_authority IN ('CREATOR','ADMIN')` |
| `catalog_listing_change` | 노출 조건 전후·버전·`actor_subject`·`authority`·`changed_at` | `uk_listing_change(product_id, catalog_version)`, `ck_listing_change_version`, `ck_listing_change_authority`, `ck_listing_change_preferences` |
| `catalog_download_change` | 제한 전후·`restriction_version`·`reason`·`actor_subject`·`changed_at` | `uk_download_change_version`, `chk_download_change_transition (previous_restricted <> restricted)`, `chk_download_change_reason`, `chk_download_change_version > 1` |
| 공통 `processed_event`·`outbox_event`·실패 보관 표·`outbox_republish` | 공통 메시징 라이브러리 | `uk_processed_event(event_id, consumer_group)`, 플랫폼 P2.2·P2.3·P2.6 |

- `productId`는 Catalog가 만든다. `releaseRequestId`를 공개본 식별자로 쓴다. `productCode`는 Studio 값을 그대로 쓴다.
- `project_id`·`product_code`·`owner_subject`의 최초 연결은 바뀌지 않는다(엔티티에 변경 메서드 없음 + 유일 키 + `CatalogProduct.requireIdentity`). 서비스 간 FK·조인은 없다.
- 판매·노출·다운로드는 서로 독립인 직교 칸이다(결정 48·50·51, 원칙 ④ "직교 필드로 적는다"). 상태 × 트리거 표는 칸 묶음별로 둔다.
- Flyway: V1~V10 체크섬 유지. V11 `catalog_product`·`catalog_release`, V12 판매(기존 행 `ON_SALE`·제한 없음·`catalog_version=content_version`), V13 다운로드(제한 없음·버전 1·`APPLIED`), V14 노출(`AUTO`·숨김 없음), V15 공개 작업·철회·요청 잠금. 목표 이행(새 번호): 상태 CHECK(MC-C1), 승인 보관·승인 잠금(MC-C2), 정지·종결(MC-C3), `NOT_REQUIRED`(CA-T3). 옛 행 이행: 작업 `PENDING`·`COMPLETED` 유지, `catalog_release` 값은 이미 허용 값 안, 버전 1 상품의 `download_storage_status`를 `NOT_REQUIRED`로. 이행 테스트는 같은 PR에 둔다(결정 104).

## 상태 모델

### 1. 출시 요청 (`catalog_publication_job.state` + `catalog_release.status`)

한 출시 요청(`releaseRequestId`)의 Catalog 안 상태는 작업 상태와 결과의 조합이다. 결과가 생기면 작업은 같은 트랜잭션에서 `COMPLETED`가 된다(불변식 CI-9). 결과 행만 있고 작업이 없는 요청(V15 이전 처리분)은 같은 결과 행으로 읽는다.

| 상태 | 작업 | 결과 | 뜻 |
|---|---|---|---|
| `NONE` | 없음 | 없음 | 아직 원요청을 접수하지 않음(선행 철회만 보관됐을 수 있음) |
| `AWAITING_APPROVAL` | `AWAITING_APPROVAL` | 없음 | 접수했으나 맞는 승인 보관이 없음(결정 167) |
| `PENDING` | `PENDING` | 없음 | 승인 대조 통과, 공개 대기 또는 일시 오류 재시도 대기 |
| `BLOCKED` | `BLOCKED` | 없음 | 재시도로 풀리지 않는 실패로 정지(결정 168) |
| `COMPLETED` | `COMPLETED` | `COMPLETED` | 공개 확정(최초·교체·같은 승인본 재출시) |
| `REJECTED` | `COMPLETED` | `REJECTED` | 거절. 사유 `STALE_CONTENT_VERSION` 또는 `APPROVAL_MISMATCH` |
| `WITHDRAWN` | `COMPLETED` | `WITHDRAWN` | 공개 확정 전 철회 |
| `CLOSED` | `COMPLETED` | `CLOSED` | 정지 작업을 운영자가 공개 없이 종결 |

트리거(열):
- **R=** 원요청 `ProjectReleaseRequested` — 같은 요청 ID·같은 원본 이벤트 ID·같은 `reviewRequestId`·같은 스냅샷 값
- **R≠** 원요청 — 같은 요청 ID에 다른 내용·다른 원본 이벤트 ID, 또는 이 원본 이벤트 ID가 다른 요청에 쓰임
- **W+** 철회 `ProjectReleaseWithdrawalRequested` — 새 `withdrawalId`, 대상 신원(요청·Project·`contentVersion`·코드·소유자) 일치
- **W=** 같은 `withdrawalId`·같은 내용 재도착
- **W≠** 같은 `withdrawalId`에 다른 내용, 또는 대상 신원 불일치
- **A=** 이 요청의 `reviewRequestId` 승인 보관(새로 도착) — 값이 출시 요청과 같음
- **A≠** 이 요청의 `reviewRequestId` 승인 보관(새로 도착) — 값이 출시 요청과 다름
- **J** 공개 작업자 실행(`PublishQueuedReleases`, 다음 시각 도래)
- **OR** ADMIN 재개 `POST …/{requestId}/resume`
- **OC** ADMIN 종결 `POST …/{requestId}/close`

| 상태 \ 트리거 | R= | R≠ | W+ | W= | W≠ |
|---|---|---|---|---|---|
| `NONE` | 허용 → 접수(R1.2): 신원·같은 버전 대조 실패면 거절(영구 실패, R2.4). 미해결 선행 철회가 있으면 → `WITHDRAWN`(작업 `COMPLETED`, 철회마다 `ProjectReleaseWithdrawn`). 없으면 승인 대조: 맞음 → `PENDING` / 보관 없음 → `AWAITING_APPROVAL` / 다름 → `REJECTED`(`APPROVAL_MISMATCH`, `ProjectReleaseRejected`) | 거절(영구 실패 — 이 이벤트 ID가 다른 요청에 쓰임) | 허용 → `NONE` 유지, 철회 미해결 보관(결과 이벤트 없음, 원요청 없는 철회 게이지) | 무변화(이미 보관) | 같은 `withdrawalId` 다른 내용: 거절(영구 실패). 신원 불일치는 원요청이 없어 이 시점에 판정할 수 없다 → 보관, 원요청 접수 때 [결정 필요 M-CA-3] |
| `AWAITING_APPROVAL` | 무변화(멱등) | 거절(영구 실패, 기존 작업 유지) | 허용 → `WITHDRAWN`, 작업 `COMPLETED`, `ProjectReleaseWithdrawn` | 무변화 | 거절(영구 실패, 철회 적용 안 함) |
| `PENDING` | 무변화 | 거절(영구 실패) | 허용 → `WITHDRAWN`, 작업 `COMPLETED`, `ProjectReleaseWithdrawn` | 무변화 | 거절(영구 실패) |
| `BLOCKED` | 무변화 | 거절(영구 실패) | 허용 → `WITHDRAWN`, 작업 `COMPLETED`, `ProjectReleaseWithdrawn`(창작자가 정지 요청을 스스로 끝낼 수 있다) | 무변화 | 거절(영구 실패) |
| `COMPLETED` | 무변화(결과 재발행 없음) | 거절(영구 실패) | 허용(결과 불변) → `ProjectReleaseWithdrawalDenied(targetStatus=COMPLETED)` | 무변화 | 거절(영구 실패) |
| `REJECTED` | 무변화 | 거절(영구 실패) | 허용(결과 불변) → `ProjectReleaseWithdrawalDenied(targetStatus=REJECTED)` | 무변화 | 거절(영구 실패) |
| `WITHDRAWN` | 무변화(공개하지 않음, RI-8) | 거절(영구 실패) | 허용(결과 불변) → 이 철회에 `ProjectReleaseWithdrawn` 회신 | 무변화 | 거절(영구 실패) |
| `CLOSED` | 무변화 | 거절(영구 실패) | 허용(결과 불변) → `ProjectReleaseWithdrawalDenied(targetStatus=CLOSED)` [결정 필요 M-CA-5] | 무변화 | 거절(영구 실패) |

| 상태 \ 트리거 | A= | A≠ | J | OR | OC |
|---|---|---|---|---|---|
| `NONE` | 무변화(승인만 보관) | 무변화(승인만 보관) | 무변화(작업 없음) | 거절 404 `NOT_FOUND` | 거절 404 |
| `AWAITING_APPROVAL` | 허용 → `PENDING`(다음 시각 = 지금) | 허용 → `REJECTED`(`APPROVAL_MISMATCH`), 작업 `COMPLETED`, `ProjectReleaseRejected` | 무변화(작업자가 고르지 않음) | 거절 409 `CONFLICT` | 거절 409 — 승인 대기 종결은 [결정 필요 M-CA-1] |
| `PENDING` | 무변화(이미 대조됨. 같은 승인의 재도착은 Inbox·보관 유일 키가 거름) | 해당 없음 — `PENDING`은 같은 `reviewRequestId`의 승인 보관과 이미 맞았고, 다른 값의 재도착은 승인 보관 단계에서 영구 실패(R11.2)라 이 요청에 닿지 않는다 | 허용 → 공개 확정 판정(아래) | 무변화 200(이미 `PENDING`) | 거절 409 |
| `BLOCKED` | 무변화 | 해당 없음 — `PENDING`과 같은 이유 | 무변화(작업자가 고르지 않음) | 허용 → `PENDING`(다음 시각 = 지금), 운영 이력 | 허용 → `CLOSED`, 작업 `COMPLETED`, 종결 기록·운영 이력·`ProjectReleaseClosed` |
| `COMPLETED` | 무변화(승인만 보관, B3) | 무변화(승인만 보관) | 무변화(작업 `COMPLETED`) | 거절 409 | 거절 409 |
| `REJECTED` | 무변화(승인만 보관, B3) | 무변화(승인만 보관) | 무변화 | 거절 409 | 거절 409 |
| `WITHDRAWN` | 무변화(승인만 보관, 작업을 열지 않음 — 1회차 지적 B3, 판정 전) | 무변화(승인만 보관) | 무변화 | 거절 409 | 거절 409 |
| `CLOSED` | 무변화(승인만 보관) | 무변화(승인만 보관) | 무변화 | 거절 409 | 무변화 200(기존 종결 기록 반환, 이벤트 없음) |

`PENDING × J`의 판정(요청 잠금 → 작업 행 잠금 → 상품 행 잠금 뒤, 한 트랜잭션):

| 조건 | 다음 상태 | 쓰는 것 |
|---|---|---|
| 보관 승인이 없거나 값이 다름(R11.6) | `BLOCKED`(`APPROVAL_NOT_VERIFIED`) | 공개 트랜잭션 롤백, 별도 트랜잭션에서 정지 기록, 경보 |
| 상품 없음, 신원 충돌 없음 | `COMPLETED` | `catalog_product` 생성, 공개본, `CatalogProductFirstPublished`, `ProjectReleaseCompleted` |
| 현재 공개본보다 높은 버전 | `COMPLETED` | 현재 참조 교체, `catalog_version = max(현재+1, contentVersion)`, 저장소 재확인 예약(제한을 쓴 상품만), `CatalogProductReleaseReplaced`, `ProjectReleaseCompleted` |
| 같은 버전·같은 스냅샷 값(다른 요청 ID) | `COMPLETED` | 결과만, 공개 사본 이벤트 없음, `ProjectReleaseCompleted`(결정 58) |
| 낮은 버전 | `REJECTED`(`STALE_CONTENT_VERSION`) | `ProjectReleaseRejected` |
| 같은 Project의 동시 최초 공개(빈 잠금 조회 뒤 커밋됨) | `PENDING`(재시도) | 재시도 가능 실패, `attempt_count`+1, 다음 시각 5·15·30·60초 |
| DB·브로커 일시 장애 | `PENDING`(재시도) | 같음 |
| 같은 버전 다른 스냅샷 / 다른 Project의 같은 코드 / 소유자·Project 불일치 / 저장 스냅샷 역직렬화 실패 | `BLOCKED`(분류 코드) | 롤백 뒤 정지 기록, 1건 경보(R12.1) |

잠금 순서(교착 방지, 모든 경로 공통): 승인 잠금(`catalog_review_gate`) → 요청 잠금(`catalog_request_gate`) → 작업 행 → 상품 행. 철회·작업자·운영 API는 승인 잠금 없이 요청 잠금부터 잡는다(같은 순서의 앞부분이 빠질 뿐이다).

### 2. 승인 보관 (`catalog_review_approval`)

| 상태 \ 트리거 | 승인 도착(같은 `reviewRequestId`, 같은 값) | 승인 도착(같은 `reviewRequestId`, 다른 값) | 출시 접수 대조 | 출시 철회·종결 |
|---|---|---|---|---|
| 없음 | 허용 → 보관, 같은 잠금 안에서 `AWAITING_APPROVAL` 작업 해제(표 1의 A=·A≠) | 해당 없음 — 비교할 보관이 없어 첫 도착은 "같은 값" 열이다 | 무변화(보관 없음 → 출시는 `AWAITING_APPROVAL`) | 무변화 |
| 보관 | 무변화(Inbox·유일 키) | 거절(영구 실패, 덮어쓰지 않음) | 무변화(읽기만) | 무변화(승인 보관은 출시 철회로 바뀌지 않는다, 결정 58) |

### 3. 출시 철회 보관 (`catalog_release_withdrawal.resolved`)

| 상태 \ 트리거 | 새 철회 도착 | 같은 `withdrawalId`·같은 내용 | 같은 `withdrawalId`·다른 내용 | 원요청 접수 | 공개 작업자 실행 |
|---|---|---|---|---|---|
| 없음 | 허용 → 미해결(원요청 없음) 또는 해결(원요청 있음, 표 1) | 해당 없음 — 행이 없으면 "새 철회"다 | 해당 없음 — 같은 이유 | 무변화 | 무변화 |
| 미해결 | 해당 없음 — 행이 있으면 같은 `withdrawalId`다 | 무변화 | 거절(영구 실패) | 허용 → 해결(`WITHDRAWN` 확정, 표 1 `NONE × R=`). 신원 불일치는 [결정 필요 M-CA-3] | 해당 없음 — 원요청이 접수되면 같은 트랜잭션에서 해결되므로 작업과 미해결 철회가 함께 있을 수 없다(CI-10) |
| 해결 | 해당 없음 | 무변화 | 거절(영구 실패) | 해당 없음 — 해결은 원요청 접수 뒤에만 생긴다 | 무변화 |

### 4. 판매 조건 (`sale_status` × `platform_restricted` → 공개 판매 상태)

트리거: **S+** CREATOR PUT `ON_SALE`, **S−** CREATOR PUT `STOPPED`, **P+** ADMIN PUT `restricted=true`, **P−** ADMIN PUT `restricted=false`, **V✗** 다른 `expectedCatalogVersion`의 PUT(모든 상태에서 409, 아래 표에서는 생략하지 않고 열로 둔다), **N** 새 공개본(교체).

| (판매자, 관리자) → 공개 | S+ | S− | P+ | P− | V✗ | N |
|---|---|---|---|---|---|---|
| (`ON_SALE`, 없음) → `ON_SALE` | 무변화 200 | 허용 → (`STOPPED`, 없음), `catalogVersion`+1, 감사, `CatalogProductSaleStatusChanged` | 허용 → (`ON_SALE`, 제한) | 무변화 200 | 거절 409 | 무변화(판매 조건 유지, R1.8) |
| (`STOPPED`, 없음) → `STOPPED` | 허용 → (`ON_SALE`, 없음) | 무변화 | 허용 → (`STOPPED`, 제한) | 무변화 | 거절 409 | 무변화 |
| (`ON_SALE`, 제한) → `BLOCKED` | 무변화 | 허용 → (`STOPPED`, 제한), 공개 값 `BLOCKED` 그대로지만 `catalogVersion`+1·감사·이벤트 | 무변화 | 허용 → (`ON_SALE`, 없음) | 거절 409 | 무변화 |
| (`STOPPED`, 제한) → `BLOCKED` | 허용 → (`ON_SALE`, 제한) | 무변화 | 무변화 | 허용 → (`STOPPED`, 없음) — 판매자 중지 유지(결정 48) | 거절 409 | 무변화 |
| 상품 없음 / CREATOR가 소유자 아님 | 거절 404 | 거절 404 | 거절 404(상품 없음) | 거절 404 | 거절 404 | 해당 없음 — 최초 공개는 표 1 |

### 5. 노출 조건 (`listing_preference` × `platform_listing_hidden` → listed)

트리거: **L-A/L-L/L-U** CREATOR PUT `AUTO`/`LISTED`/`UNLISTED`, **H+/H−** ADMIN PUT `hidden=true/false`, **V✗** 다른 버전 PUT, **S** 판매 조건 변경(표 4), **N** 새 공개본. 표의 "→"는 노출 조건 다음 값, 바뀌면 `catalogVersion`+1·`catalog_listing_change`·`CatalogProductListingChanged`.

| (선호, 숨김) | L-A | L-L | L-U | H+ | H− | V✗ | S | N |
|---|---|---|---|---|---|---|---|---|
| (`AUTO`, 없음) | 무변화 | 허용 → (`LISTED`, 없음) | 허용 → (`UNLISTED`, 없음) | 허용 → (`AUTO`, 숨김) | 무변화 | 거절 409 | 노출 조건 무변화, listed는 공개 판매 상태로 다시 계산(`ON_SALE`일 때만 true), 이벤트는 표 4의 판매 이벤트 하나 | 무변화 |
| (`LISTED`, 없음) | 허용 → (`AUTO`, 없음) | 무변화 | 허용 → (`UNLISTED`, 없음) | 허용 → (`LISTED`, 숨김) | 무변화 | 거절 409 | 무변화(listed=true 유지) | 무변화 |
| (`UNLISTED`, 없음) | 허용 → (`AUTO`, 없음) | 허용 → (`LISTED`, 없음) | 무변화 | 허용 → (`UNLISTED`, 숨김) | 무변화 | 거절 409 | 무변화(listed=false 유지) | 무변화 |
| (`AUTO`, 숨김) | 무변화 | 허용 → (`LISTED`, 숨김), listed=false 유지 | 허용 → (`UNLISTED`, 숨김) | 무변화 | 허용 → (`AUTO`, 없음) | 거절 409 | 무변화(숨김 우선) | 무변화(숨김 유지) |
| (`LISTED`, 숨김) | 허용 → (`AUTO`, 숨김) | 무변화 | 허용 → (`UNLISTED`, 숨김) | 무변화 | 허용 → (`LISTED`, 없음) | 거절 409 | 무변화 | 무변화 |
| (`UNLISTED`, 숨김) | 허용 → (`AUTO`, 숨김) | 허용 → (`LISTED`, 숨김) | 무변화 | 무변화 | 허용 → (`UNLISTED`, 없음) | 거절 409 | 무변화 | 무변화 |
| 상품 없음 / CREATOR가 소유자 아님 | 거절 404 | 거절 404 | 거절 404 | 거절 404(상품 없음) | 거절 404 | 거절 404 | 해당 없음 | 해당 없음 — 최초 공개는 표 1 |

### 6. 다운로드 제한 (`download_restricted` × `download_storage_status`)

트리거: **D+/D−** ADMIN PUT `restricted=true/false`(같은 `expectedRestrictionVersion`), **DV✗** 다른 버전 PUT, **K✓** 저장소 적용 작업 성공(호출 전 읽은 제한 버전 그대로), **K✗** 적용 작업 실패, **K~** 적용 작업이 끝났으나 그 사이 제한 버전이 바뀜, **N** 새 공개본(교체). 다운로드 허용(내부 빌드 조회 200)은 (해제, `NOT_REQUIRED`)·(해제, `APPLIED`)뿐이다(CI-12).

| (제한, 적용) | D+ | D− | DV✗ | K✓ | K✗ | K~ | N |
|---|---|---|---|---|---|---|---|
| (해제, `NOT_REQUIRED`) | 허용 → (제한, `PENDING`), 버전+1, 이력, 202 | 무변화 202(이력 없음) | 거절 409 | 해당 없음 — 적용 대상이 아니라 작업이 고르지 않는다 | 해당 없음 — 같은 이유 | 해당 없음 — 같은 이유 | 무변화(재확인 예약 없음, R6.7) |
| (제한, `PENDING`) | 무변화 202 | 허용 → (해제, `PENDING`), 버전+1, 이력 | 거절 409 | 허용 → (제한, `APPLIED`), 확인 시각, 다음 재확인 +60초 | 허용 → (제한, `FAILED`), 다음 +30초 | 무변화(결과 버리고 새 원하는 상태로 다음 실행) | 무변화(이미 예약됨, 새 공개본 객체도 다음 실행에 포함) |
| (제한, `APPLIED`) | 무변화 202 | 허용 → (해제, `PENDING`) | 거절 409 | 무변화 상태, 재확인 시각 갱신(어긋남이 있었으면 고친 뒤) | 허용 → (제한, `FAILED`) | 무변화 | 허용 → (제한, `PENDING`) |
| (제한, `FAILED`) | 무변화 202 | 허용 → (해제, `PENDING`) | 거절 409 | 허용 → (제한, `APPLIED`) | 무변화(`FAILED`, 다음 +30초) | 무변화 | 허용 → (제한, `PENDING`) |
| (해제, `PENDING`) | 허용 → (제한, `PENDING`) | 무변화 202 | 거절 409 | 허용 → (해제, `APPLIED`) | 허용 → (해제, `FAILED`) | 무변화 | 무변화 |
| (해제, `APPLIED`) | 허용 → (제한, `PENDING`) | 무변화 202 | 거절 409 | 무변화 상태, 재확인 시각 갱신 | 허용 → (해제, `FAILED`) | 무변화 | 허용 → (해제, `PENDING`) |
| (해제, `FAILED`) | 허용 → (제한, `PENDING`) | 무변화 202 | 거절 409 | 허용 → (해제, `APPLIED`) | 무변화(`FAILED`, 다음 +30초) | 무변화 | 허용 → (해제, `PENDING`) |
| 상품 없음 | 거절 404 | 거절 404 | 거절 404 | 해당 없음 | 해당 없음 | 해당 없음 | 해당 없음 — 최초 공개는 (해제, `NOT_REQUIRED`) |

저장소 적용이 구성되지 않은 인스턴스(`UnavailableBuildDistribution`)에서 D+·D−는 503 `UPSTREAM_UNAVAILABLE`이고 상태를 바꾸지 않는다.

## API 계약

공통: 비로그인 401 `UNAUTHORIZED`, 역할 위반 403 `FORBIDDEN`, 남의 상품·없는 상품 404 `NOT_FOUND`, 버전 불일치·상태 위반 409 `CONFLICT`, 누락·형식 오류 400 `INVALID_REQUEST`. 응답 본문은 `ApiResponse`. 공개 목록·상세·관리·판매·노출·작업·내부 응답은 모두 `Cache-Control: no-store`. 쓰기 명령의 멱등은 "현재 버전의 같은 값 = 무변화" 규칙(버전 대조)으로 지키고 별도 멱등 키는 두지 않는다.

| 메서드 | 경로 | 역할 | 요청 | 성공 응답 | 오류 |
|---|---|---|---|---|---|
| GET | `/api/v1/products?page&size` | 공개 | `page`≥0(기본 0), `size` 1~100(기본 20) | 200 listed=true 페이지(최초 공개 시각·`productId` 내림차순) | 400 |
| GET | `/api/v1/products/by-code/{productCode}` | 공개 | — | 200 현재 공개본 상세·공개 판매 상태·`catalogVersion`·genres | 404(없음·listed=false) |
| GET | `/api/v1/products/by-code/{code}/sales` | CREATOR(소유자) | — | 200 판매자 의사·공개 판매 상태·`catalogVersion` | 404 |
| PUT | `/api/v1/products/by-code/{code}/sales` | CREATOR(소유자) | `saleStatus`(`ON_SALE`/`STOPPED`), `expectedCatalogVersion` | 200 | 400, 404, 409 |
| GET·PUT | `/api/v1/admin/products/{code}/sales/restriction` | ADMIN | PUT: `restricted`(필수 Boolean), `expectedCatalogVersion` | 200 | 400, 404, 409 |
| GET·PUT | `/api/v1/products/by-code/{code}/listing` | CREATOR(소유자) | PUT: `preference`(`AUTO`/`LISTED`/`UNLISTED`), `expectedCatalogVersion` | 200 | 400, 404, 409 |
| GET·PUT | `/api/v1/admin/products/{code}/listing/restriction` | ADMIN | PUT: `hidden`(필수 Boolean), `expectedCatalogVersion` | 200 | 400, 404, 409 |
| GET·PUT | `/api/v1/admin/products/{code}/downloads/restriction` | ADMIN | PUT: `restricted`, `expectedRestrictionVersion`, `reason`(1~500자) | GET 200 / PUT 202(접수, 저장소 적용 상태 포함) | 400, 404, 409, 503 `UPSTREAM_UNAVAILABLE` |
| GET | `/api/v1/admin/catalog-release-jobs?state&page&size` | ADMIN | `state` ∈ {`AWAITING_APPROVAL`,`PENDING`,`BLOCKED`}(기본 `PENDING`) | 200 작업 목록(상태·시도·`lastErrorCode`·`lastError`·`nextAttemptAt`·`sourceEventId`·`blockedAt`) | 400 |
| GET | `/api/v1/admin/catalog-release-jobs/{requestId}` | ADMIN | — | 200 작업 하나(+출시 결과·종결 기록) | 404 |
| POST | `/api/v1/admin/catalog-release-jobs/{requestId}/resume`(목표) | ADMIN | `reason`(필수) | 200 작업(`PENDING`) | 400, 404, 409 |
| POST | `/api/v1/admin/catalog-release-jobs/{requestId}/close`(목표) | ADMIN | `reason`(필수) | 200 종결 기록 | 400, 404, 409 |

내부 전용(Gateway·공개 OpenAPI 미노출, `/internal/**`는 별도 보안 체인, 키는 상수 시간 비교, 키 미설정이면 그 경로는 항상 거절, 각 키는 자기 경로 GET에만 권한):

| 경로 | 호출 모듈 · 키 헤더 · 설정 | 응답 |
|---|---|---|
| `GET /internal/v1/store-products?page&size`, `/{code}` | Store · `X-Store-Service-Key` · `stove.catalog.store-service-key`(`CATALOG_STORE_SERVICE_KEY`) | 숨긴 상품 포함 공개 사본, 상세에 필수 listed |
| `GET /internal/v1/order-products/{code}` | Order · `X-Order-Service-Key` · `stove.catalog.order-service-key` | 상품 ID·코드·제목·`contentVersion`·금액·통화·공개 판매 상태·`publicDetailAllowed`(R7.2) |
| `GET /internal/v1/products/{code}/owner` | Settlement · `X-Settlement-Service-Key` · `stove.catalog.settlement-service-key` | `productId`·`productCode`·`ownerSubject`(불변) |
| `GET /internal/v1/products/{code}/build` | Download · `X-Download-Service-Key` · `stove.catalog.download-service-key` | 현재 공개본의 `productId`·`releaseRequestId`·`contentVersion`·`buildId`·`version`·`fileSize`·`checksum`·`storagePath`. 다운로드 불가(CI-12)면 403 `PRODUCT_DOWNLOAD_RESTRICTED`, 현재 공개본 손상·결과 불일치면 500 `INTERNAL_ERROR` |

공통 운영 API(`/api/v1/ops/dlt`·`/api/v1/ops/outbox/dead`·재발행)는 공통 메시징 라이브러리 계약이며 기본 비활성, 켜면 ADMIN 단건만이다(결정 87, 플랫폼 P2.2-7·P2.6-4).

## 이벤트

이벤트의 뜻·키·순서·소비 그룹·멱등 키는 [플랫폼 design "이벤트 목록"](../platform/design.md#이벤트-목록)이 정본이다. 여기에는 Catalog의 리스너·검증·서비스·업무 멱등 키·실패 처리만 적는다.

### 소비

| 이벤트 · 플랫폼 행 | 그룹 · 토픽 | 리스너 → Mapper | 서비스 | 업무 멱등 키 | 실패 처리 |
|---|---|---|---|---|---|
| `ProjectReleaseRequested` · design "리터럴" 표, 흐름 1 S4 | `catalog-project-release` · `stove.studio.v1` | `StudioReleaseEventListener` → `ReleaseSubmissionMapper.from`(`eventId`·`eventType`·키=`productCode`), `ReleaseSubmission` 생성자 값 검증 | `ReceiveProjectRelease`(R1.2·R11.3·R11.4·R9.5) | `catalog_publication_job` PK `request_id`·`uk_catalog_publication_event(source_event_id)`, `catalog_release` PK·`uk_catalog_request_event` | 형식·계약 위반·식별 충돌·같은 키 다른 내용 = 영구 실패(재시도 없이 실패 보관 표 + 키 정지, 플랫폼 P2.6-3~5). DB 일시 장애 = 재시도 가능 |
| `ProjectReleaseWithdrawalRequested` · 같은 표, 흐름 1 S5 | 같은 그룹 · 같은 토픽 | 같은 리스너 → `ReleaseWithdrawalMapper.from`, `ReleaseWithdrawalInput` | `ReceiveReleaseWithdrawal`(R9) | `catalog_release_withdrawal` PK `withdrawal_id` | 같은 `withdrawalId` 다른 내용·대상 신원 불일치 = 영구 실패 |
| `ProjectReviewApproved` · 같은 표 "(목표) Catalog · `catalog-review-approval`", 흐름 1 S3a | `catalog-review-approval`(목표) · `stove.review.v1` | `ReviewApprovalEventListener`(목표) → `ReviewApprovalMapper.from`(봉투·키=`productCode`·본문 값) | `ReceiveReviewApproval`(목표, R11.1·R11.5) | `catalog_review_approval` PK `review_request_id`·`uk_catalog_review_approval_event` | 같은 `reviewRequestId` 다른 값 = 영구 실패. 다른 `eventType`은 건너뛰고 지표(R11.9) |

- 같은 그룹이 같은 토픽·같은 키(`productCode`)로 원요청과 철회를 받으므로 둘의 Kafka 도착 순서는 Studio 커밋 순서다(플랫폼 P2.5-1). 승인은 다른 토픽이라 순서 보장이 없고, Catalog는 보관 유무와 값으로만 판정한다(R11.7).
- 여러 이벤트 타입을 받는 `StudioReleaseEventListener`는 처리하지 않는 타입을 건너뛴 수를 (그룹, 타입) 지표로 낸다(플랫폼 P2.3-7, T31). `catalog-review-approval`은 토픽 하나만 받는다.
- 실패 보관·키 정지·재투입은 공통 메시징 라이브러리가 구성한다(결정 155·160). 원요청이 영구 실패로 정지되면 같은 `productCode`의 뒤 레코드(그 요청의 철회 포함)도 정지된다 — 회수는 원인을 고친 뒤 키 단위 재투입이다.

### 발행

| 이벤트 · 플랫폼 행 | 토픽 · 키 | 발행 지점(같은 트랜잭션) | 이 모듈의 보장 |
|---|---|---|---|
| `ProjectReleaseCompleted` · 리터럴 표 | `stove.release.v1` · `productCode` | `PublishProjectRelease`(공개 확정) | 결과 행 INSERT와 같은 트랜잭션, 요청당 한 번(`catalog_release` PK). `requestEventId` = 원요청 이벤트 ID |
| `ProjectReleaseRejected` · 리터럴 표 | 같음 | `PublishProjectRelease`(`STALE_CONTENT_VERSION`), `ReceiveProjectRelease`·`ReceiveReviewApproval`(`APPROVAL_MISMATCH`) | `reason`은 `ReleaseRejectReason` enum 값만(플랫폼 design "`ProjectReleaseRejected.reason`은 enum") |
| `ProjectReleaseWithdrawn` · 목표 행(SP-8 ①) | 같음 | `ReceiveReleaseWithdrawal`, `ReceiveProjectRelease`(선행 철회) | 철회마다 하나. 업무 키 `withdrawalId`(결정 176) |
| `ProjectReleaseWithdrawalDenied` · 목표 행(SP-8 ②) | 같음 | `ReceiveReleaseWithdrawal` | `targetStatus` enum {`COMPLETED`,`REJECTED`}(+`CLOSED`, M-CA-5). 대상 판정의 `actor`·`feedback`을 싣지 않는다 |
| `ProjectReleaseClosed`(가칭) · 목표 행 | 같음 | `CloseBlockedRelease`(목표, 운영자 종결) | 결과 `CLOSED` INSERT와 같은 트랜잭션, `Completed`·`Rejected`·`Withdrawn`과 배타(PK). 본문: `releaseRequestId`·`projectId`·`contentVersion`·`productCode`·`requestEventId`·`closedBy`·`reason`·`blockCode` |
| `CatalogProductFirstPublished` · `CatalogProductReleaseReplaced` · 목표 행(SP-4 ①②) | `stove.catalog.v1` · `productCode` | `PublishProjectRelease` | 공개 사본이 바뀐 공개 확정에서만(같은 승인본 재출시 제외). 본문 `CatalogProductSnapshot` 전체 + `catalogVersion` |
| `CatalogProductSaleStatusChanged` · 목표 행(SP-4 ③) | 같음 | `RecordCatalogSaleChange` | 판매 조건이 실제로 바뀐 명령에서만, 감사 행과 같은 트랜잭션 |
| `CatalogProductListingChanged` · 목표 행(SP-4 ④) | 같음 | `ChangeCatalogListing` | 노출 조건이 실제로 바뀐 명령에서만 |

- 옛 `ProjectReleaseWithdrawalResolved`·`CatalogProductPublished`는 플랫폼 P2.7-4 순서로 이행한 뒤 지운다(T21·T22).
- 모든 발행 이벤트는 헤더 `schemaVersion`을 싣고(결정 170), 재발행 허용 목록은 플랫폼 이벤트 표의 Catalog 현행 행에서 만든다(결정 161, `outbox_republish`·`republish-owner=catalog`).
- 공개 사본 이벤트의 `publishedAt`·`updatedAt`은 Catalog 시각이고 Store는 값으로만 쓴다. Store의 판정은 `catalogVersion`이다(플랫폼 P2.3-5).

## 모듈 안 불변식

모듈을 넘는 정합 조건은 플랫폼 RI-1~RI-9·I 번호를 따른다. Catalog가 지키는 플랫폼 조건: RI-2(목표: CI-8), RI-3(CI-1), RI-4(결과 발행 쪽), RI-8(CI-7), RI-9(CI-6), P1.4-6(CI-5), P1.4-7(CI-12).

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| CI-1 | `current_release_request_id`가 가리키는 결과는 `COMPLETED`이고 그 Project의 `COMPLETED` 중 `content_version`이 가장 크다(RI-3) | 상품 행 잠금 + 버전 비교 `catalog:core/service/PublishProjectRelease.java:79-126`, 읽을 때 재확인 `ReadPublishedBuild.java:45-53`, `fk_catalog_current_release` | `CatalogPublicationIntegrationTest#concurrentFirstPublicationAndDuplicateDeliveriesConvergeToHighestVersion`, `#publishesReplacesRejectsStaleAndKeepsOriginalResults` |
| CI-2 | 결과 행·스냅샷은 INSERT 뒤 바뀌지 않는다(공개본 불변) | `CatalogRelease`에 변경 메서드 없음, `entityManager.persist`만(`PublishProjectRelease.java:113`) | 없음 → tasks CA-T8 |
| CI-3 | 출시 요청당 결과는 하나다 | `catalog_release` PK, 요청 잠금 | `CatalogPublicationIntegrationTest#requestAndVersionConflictsDoNotOverwriteOrMarkInbox` |
| CI-4 | 같은 Project·같은 `contentVersion`의 접수·결과 스냅샷은 값이 같다 | `ReceiveProjectRelease.java:78-87`, `PublishProjectRelease.java:90-97` | `#requestAndVersionConflictsDoNotOverwriteOrMarkInbox` |
| CI-5 | `project_id`·`product_code`·`owner_subject` 연결은 처음 값 그대로다 | `uk_catalog_project`·`uk_catalog_product_code`, `CatalogProduct.requireIdentity`(`CatalogProduct.java:137-143`), 변경 메서드 없음 | `#differentProjectWithSameProductCodeStaysConflict`, `CatalogProductTest#publicationPreservesIdentityAndFirstPublicationTime` |
| CI-6 | `catalog_version`은 상품마다 단조 증가하고 현재 공개본 `content_version` 이상이다(RI-9) | `CatalogProduct.java:72·81·133·148·158·167`, `ck_catalog_version > 0` | 단조성 전용 단언 미확인 → tasks CA-T8 |
| CI-7 | `WITHDRAWN`·`REJECTED`·`CLOSED` 요청은 현재 공개본이 된 적이 없고 공개 사본 이벤트를 내지 않았다(RI-8) | 요청 잠금 안 결과 존재 확인 + 선행 철회 우선(`PublishProjectRelease.java:54-61·106-121`) | `ReleaseWithdrawalIntegrationTest#aWithdrawnUpdateKeepsTheEarlierPublicBuildAndIndependentControls`, `#withdrawalBeforeOriginalWaitsForTheOriginalAndCannotBeReversedByReplay` |
| CI-8 | 결과 `COMPLETED`인 요청에는 `(reviewRequestId, projectId, contentVersion, productCode, 스냅샷 값)`이 같은 승인 보관이 있다(RI-2의 Catalog 쪽) | 목표: 접수·승인 해제 때 대조(R11.3~5) + 공개 트랜잭션 재확인(R11.6) | 없음 → MC-C2 |
| CI-9 | 결과 행이 있으면 같은 요청의 작업은 `COMPLETED`다(작업이 있는 요청) | 목표: 결과 INSERT와 작업 `COMPLETED`를 같은 트랜잭션에(지금 철회 경로는 작업을 두고 감, CA-T5) | 없음 → CA-T5 |
| CI-10 | 작업이 있는 요청에는 미해결 철회가 없다 | 원요청 접수 트랜잭션에서 선행 철회 해결(`ReceiveProjectRelease.java:62`), 같은 요청 잠금 | `#withdrawalBeforeOriginalWaitsForTheOriginalAndCannotBeReversedByReplay` |
| CI-11 | 승인 보관은 `reviewRequestId`당 하나이고 바뀌지 않는다 | 목표: PK + INSERT만 | 없음 → MC-C2 |
| CI-12 | 내부 빌드 조회는 (해제, `NOT_REQUIRED`) 또는 (해제, `APPLIED`)일 때만 200이다 | `CatalogProduct.isDownloadUnavailable`(`CatalogProduct.java:86-88`), `ReadPublishedBuild.java:31-32` | `CatalogDownloadRestrictionIntegrationTest#newIssueGateAndEveryPreviouslyPublishedObjectAreBlockedAndExplicitlyRestored`, `#failedUnblockKeepsTheGateAndAFreshWorkerLoadsTheDurableDesiredState` |
| CI-13 | 판매·노출 감사 행의 `catalog_version = previous_version + 1`이고 상품·버전당 하나다 | `ck_catalog_sale_change_version`·`uk_catalog_sale_change`, `ck_listing_change_version`·`uk_listing_change` | `CatalogSalesIntegrationTest#simultaneousCommandsUsingOneVersionCannotOverwriteEachOther`, `CatalogListingIntegrationTest#concurrentSellerAndAdministratorCommandsHaveOnlyOneWinningVersion` |
| CI-14 | 다운로드 이력은 실제 전환만, 공백 아닌 사유와 함께 남는다 | `chk_download_change_transition`·`chk_download_change_reason`·`chk_download_change_version` | `CatalogDownloadMigrationIntegrationTest#existingSalesAndPublicationArePreservedAndInvalidWorkCannotBeStored` |
| CI-15 | listed 규칙은 한 곳에서 정의된다 | `ListingPreference.isListed`·`CatalogProduct.isListed`. 지금 목록 쿼리(`CatalogProductRepository.java:15`)가 같은 규칙을 JPQL로 다시 적는다(CA-T6) | `CatalogListingIntegrationTest#automaticDefaultTracksSalesAndIndependentOverridesAlsoControlPublicDetail`(목록·상세 일치 단언 여부 미확인) |
| CI-16 | 운영자 종결은 `BLOCKED`이고 결과 없는 요청에만 일어난다 | 목표: 요청 잠금 → 작업 행 잠금 뒤 상태 확인(R12.4) | 없음 → MC-C3 |

## 외부 연동과 adapter 메모

- `core.port.BuildDistribution` — `requireConfigured()`, `apply(List<DownloadBuildReference>, boolean restricted)`. 구현 `adapter/storage/S3BuildDistribution`(구성됨), `UnavailableBuildDistribution`(미구성: `UPSTREAM_UNAVAILABLE`).
- `core.port.PublicationTracing` — 접수 때 추적 부모를 저장하고 작업마다 복원(결정 28). 구현 `adapter/trace/MicrometerPublicationTracing`·`DisabledPublicationTracing`.
- adapter 메모(공급자 규칙, core 계약 아님): S3/MinIO 객체 태그 `stove-download-blocked`를 `GetObjectTagging`·`PutObjectTagging`만 허용된 전용 계정(`CATALOG_DOWNLOAD_STORAGE_*`)으로 쓴다. 확인은 Download와 같은 읽기 전용 계정(`DOWNLOAD_STORAGE_*`)의 1바이트 범위 GET — 차단이면 `AccessDenied`(버킷 정책 `infra/minio/policies/download-reader.json:19`가 태그로 거절), 해제면 성공. 객체 키는 `projects/{projectId}/` 경계를 먼저 확인하고 벗어나면 `FAILED`. 태그 수 한도 초과는 `FAILED`("No object tag capacity"). 호출 제한 기본 5초(상한 10초). 저장소 오류 메시지·URL·자격증명은 `last_error`에 남기지 않는다(`ReconcileDownloadStorage.java:62-65`). 저장 경로 형식(`s3://` 스킴)은 저장소 adapter의 규칙이며 core 입력 검증에서 공급자 스킴을 요구하지 않는다(CA-T9).
- 저장소 적용 작업 흐름(목표, R6.4): ① 짧은 트랜잭션으로 대상 상품의 제한 버전·원하는 상태·`COMPLETED` 공개본 객체 목록을 읽는다 ② 트랜잭션 밖에서 `apply` ③ 새 트랜잭션에서 상품 행을 잠그고 제한 버전이 ①과 같을 때만 `APPLIED`/`FAILED`를 기록, 다르면 결과를 버린다(표 6 K~). 같은 상품의 동시 적용은 다음 시각을 미리 미루는 선점으로 막는다(CA-T2).

## 설정

| 설정 키 | 기본값 | 뜻 | 운영·테스트 차이 |
|---|---|---|---|
| `stove.catalog.publication.poll-interval-ms` | 200 | 공개 작업자 주기 | 없음. 최소 철회 시간이 아니다(R9.6) |
| `stove.catalog.publication.batch-size` | 20(1~100) | 한 주기 처리 작업 수 | 없음 |
| `stove.catalog.publication.worker-enabled` | true | 공개 작업자 켜기 | 테스트·검증 도구가 false로 쓴다(CA-T10). 운영 용도는 DB 전환 절차의 작업자 정지 |
| `stove.catalog.download-storage.enabled` (`CATALOG_DOWNLOAD_STORAGE_ENABLED`) | false | 저장소 적용 구성 여부. false면 다운로드 제한 PUT 503 | 운영 배포는 true와 두 계정을 명시 주입(결정 86) |
| `stove.catalog.download-storage.worker-enabled` | true | 저장소 적용 작업 켜기 | CA-T10과 같음 |
| `stove.catalog.download-storage.poll-interval-ms` | 5000 | 적용 작업 주기 | 없음 |
| `stove.catalog.download-storage.{endpoint, region, bucket, access-key, secret-key, reader-access-key, reader-secret-key, call-timeout}` | region `us-east-1`, call-timeout 5s(상한 10s) | adapter 조립(`DownloadStorageProperties`). 쓰기·읽기 계정이 같으면 기동 실패 | 없음 |
| `stove.catalog.{store,order,settlement,download}-service-key` | 빈 값 | 내부 경로 키. 비면 그 경로 거절 | 없음(빈 키를 허용하는 테스트 경로 없음, `UnconfiguredServiceKeyIntegrationTest`) |
| 공개 작업 재시도 간격 5·15·30·60초 | 코드 상수(`CatalogPublicationJob.failed`) | Catalog 업무 규칙(R10.3) | 없음 |
| 저장소 재시도 +30초·재확인 +60초 | 코드 상수(`CatalogProduct.downloadStorage*`) | Catalog 업무 규칙(R6.5) | 없음 |

설정 바인딩은 `config`(`PublicationConfiguration`·`DownloadStorageConfiguration`·`SecurityConfig`)만 하고 서비스에는 값만 넘긴다.
