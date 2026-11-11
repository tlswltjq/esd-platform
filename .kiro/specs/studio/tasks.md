# 작업: Studio

> 형식: 무엇이 · 어디서(`파일:줄`) · 목표 · 영향 · 재현 테스트. 경로 줄임 `studio:` = `apps/studio/src/main/java/com/stove/studio/`. 줄 번호는 `docs/spec-foundation` 작업 폴더 기준. 모듈을 넘는 항목은 [플랫폼 tasks](../platform/tasks.md)의 T번호를 링크한다.

## 완료 (검증 결과는 [근거 색인](../../../docs/evidence.md))

- [x] Project 중심 축소(APPROVED/PUBLISHED·공개본 참조·`/publish` 제거), 업로드·요청·외부 결과 기록 — 2026-10-14 (`79cc163`, `37f9807`)
- [x] 목록 페이지(requirements R8) — 2026-11-03
- [x] 장르 선택(requirements R2, V27) — 2026-11-03
- [x] 프로젝트 전체 단일 심사·출시, requestId·contentVersion 명령 멱등, 심사·출시 철회 내구 접수와 결과 수신(requirements R3~R7, V26) — 2026-11-03
- [x] Studio 자료→Catalog 공개→Store 조회를 잇는 G4 전체 운영 여정의 최신 전체 완료 — 2026-11-08, main `8c113393` 실제 9개 앱 12단계 (`verify-g4-operations.py`, [근거 색인](../../../docs/evidence.md) 2026-11-08 G4 행)
- [x] 다수 서비스 동시 기동 시 DB 최초 접속 기한 복원력(DB를 쓰는 9개 앱 공통) — 2026-11-09, 부팅 때만 첫 연결 60초 재시도·실행 중 `connection-timeout` 3초 유지([결정 136](../../../docs/decisions.md#db-startup-wait)). `DatabaseStartupWaitContractTest`·`DatabaseStartupWaitIntegrationTest`

## 코드와 맞출 것

영향 순(보유권·출시 정합 → 이벤트 계약 → 상태 표현 → 그 밖). 돈에 직접 닿는 항목은 없다(Studio는 금액을 정하지 않고 가격은 스냅샷 값으로만 넘긴다). 보유권에는 출시 결과·공개본 정합을 통해 간접으로 닿는다.

- [ ] **MC-ST2. 모순된 결과를 조용히 무시한다(최종 요청에 다른 결과, 원요청 이벤트 ID가 다른 출시 결과)**
  - 어디서: `studio:core/domain/review/ProjectReviewRequest.java:90`(`if (!"REQUESTED".equals(status)) return;`), `studio:core/domain/release/ProjectReleaseRequest.java:65`, `studio:core/service/release/RecordProjectReleaseResult.java:35`(`requestEventId`가 다르면 `return`).
  - 목표: design 상태 모델의 심사 요청 표 E1·E2, 출시 요청 표 E6·E7 — 같은 결과는 무변화, 다른 결과·다른 `requestEventId`는 영구 실패([P3.3-3](../platform/requirements.md#p33-심사-판정-결과-review--studio)·[P3.6-2](../platform/requirements.md#p36-출시-결과-기록-catalog--studio), R7.4).
  - 영향: **보유권(간접)** — Studio `WITHDRAWN`·Catalog `COMPLETED`처럼 두 모듈의 최종 상태가 갈라져도(RI-4 위반) 아무도 모른다. 철회된 출시가 늦은 승인으로 공개되는 경우(**1회차 지적 B3, 판정 전**)가 Studio에서 드러나는 유일한 자리다.
  - 재현: `WITHDRAWN` 출시 요청에 `ProjectReleaseCompleted`를 보내면 지금은 오프셋이 커밋되고 Studio가 `WITHDRAWN`으로 남는다 → 목표는 영구 실패. `ProjectReviewRequestTest#contradictoryLateResultsDoNotOverwriteTheRecordedRejection`(지금 무시를 단언)을 영구 실패 단언으로 바꾼다.

- [ ] **ST-T2. 운영자 출시 종결(`ProjectReleaseClosed`, 가칭)을 받지 못하고 `CLOSED` 상태가 없다** ([결정 168](../../../docs/decisions.md#publication-blocked-closure), 플랫폼 [T23](../platform/tasks.md), MC-C3)
  - 어디서: `studio:api/listener/ProjectResultListener.java:33-48`(분기 없음), `ProjectReleaseRequest.java:37-38`(상태 값), V26 CHECK 없음.
  - 목표: R7.3·R7.7, design 출시 요청 표 E8·E12. `ReleaseRequestStatus.CLOSED`, `closed_by`·`close_reason` 칸, `RecordProjectReleaseClosure`, 판정표 11에서 `CLOSED` 뒤 새 요청 허용.
  - 영향: **보유권** — Catalog가 정지 작업을 종결해도 Studio 활성 출시 자리가 풀리지 않아 그 Project의 다음 출시(업데이트)가 모두 409, 기존 보유자가 새 빌드를 받지 못한다.
  - 재현: `REQUESTED` 출시 요청에 `ProjectReleaseClosed` 견본을 보내 `CLOSED`·자리 해제·새 `requestId` 출시 202를 단언(지금 건너뜀).

- [ ] **ST-T1. 철회 결과 이벤트 분리를 받는 쪽 — 영수증 칸이 두 뜻을 겸한다** ([결정 176](../../../docs/decisions.md#withdrawal-result-events), 플랫폼 [T21](../platform/tasks.md))
  - 어디서: `ProjectResultListener.java:33-38`, `studio:api/listener/ProjectWithdrawalResultMapper.java:25-33`, `studio:core/domain/project/ProjectRequestCommand.java:37-39·93-124`(`final_status`가 `WITHDRAWN`과 대상 판정을, `final_actor`·`final_feedback`이 철회자/판정자·철회 사유/반려 사유를 겸함, `:113`이 `finalizedAt` 같음으로 재전달 판정).
  - 목표: R6.2·R7.6, design 철회 명령 표. 리스너가 `ProjectReviewWithdrawn`·`…WithdrawalDenied`·`ProjectReleaseWithdrawn`·`…WithdrawalDenied`와 이행 중 옛 `…Resolved`를 같은 내부 입력으로 바꾼다. 명령 칸은 `denied_target_status`로, `final_actor`·`final_feedback`은 지운다. 재전달 판정은 `withdrawalId`·상태·`targetStatus`로. 영수증 응답에서 `finalStatus`·`finalActor`·`finalFeedback`을 빼고 `deniedTargetStatus`를 넣는다. P2.7-4 순서(Studio가 옛·새 타입을 모두 받는 판이 먼저).
  - 영향: 돈 없음. 창작자 화면이 `finalActor`를 철회자로 읽으면 판정 뒤 철회의 경우 심사자 이름을 철회자로 보인다.
  - 재현: 판정 뒤 철회 → 영수증 `finalActor`가 심사자임을 단언(지금 통과) → 목표 계약 테스트로 바꾸면 Red.

- [ ] **ST-T4. 심사 요청의 `actor` 한 칸이 판정자와 철회자를 겸한다**
  - 어디서: `ProjectReviewRequest.java:49`, `:80-83`(`WITHDRAWN`이면 `actor`=철회자, 그 밖은 판정자).
  - 목표: design `studio_review_request` — `decided_by`(판정자만)·`withdrawn_by`(철회자) 분리와 칸 조합 CHECK. 출시 요청은 이미 `withdrawn_by`를 따로 갖는다.
  - 영향: 돈 없음. 원칙 ①(칸 하나에 뜻 하나) 위반. API의 `actor`를 판정자로 읽는 화면이 철회 행에서 틀린다.
  - 재현: 철회 확정 뒤 `actor` = 창작자 `sub`임을 단언(지금) → 목표는 `decidedBy` NULL·`withdrawnBy` = `sub`.

- [ ] **MC-ST1. 여러 토픽을 받는 `ProjectResultListener`가 (토픽, `eventType`) 짝을 대조하지 않는다**
  - 어디서: `ProjectResultListener.java:28-48`.
  - 목표: R7.8, [P2.3-8](../platform/requirements.md#p23-소비--inbox와-업무-멱등).
  - 영향: 다른 모듈이 같은 이름의 타입을 잘못된 토픽에 내면 받아들인다(Review가 출시 결과를 위조할 수 있는 경로).
  - 재현: review 토픽에 `ProjectReleaseCompleted`를 넣고 영구 실패 단언.

- [ ] **ST-T8. 소비 실패를 분류하지 않고, `productCode`를 Project와 대조하지 않는다** (플랫폼 [T20](../platform/tasks.md))
  - 어디서: `studio:core/service/review/RecordProjectReviewResult.java:26-33`·`RecordProjectReleaseResult.java:28-34`(대상 없음 `NOT_FOUND`, 불일치 `CONFLICT` — 모두 공통 핸들러가 4회 재시도), `ProjectResultMapper.java:42-50`(봉투 키 = 본문 `productCode`만 확인, Project의 코드와는 대조 안 함).
  - 목표: design "이벤트 > 소비" 실패 분류. 대상 없음·식별자 불일치·모순은 공통 영구 실패 예외, `productCode` = `studio_project.product_code` 대조(ST-I10).
  - 영향: 계약 위반 한 건이 같은 `productCode` 키를 7초 넘게 멈춘다. ID가 맞고 코드가 다른 결과가 기록된다.
  - 재현: 다른 `productCode`의 승인 결과 → 지금 `APPROVED` 기록 → 목표 영구 실패, 시도 1회.

- [ ] **MC-ST3. 흐름 상태가 문자열이고 CHECK가 없다**
  - 어디서: `ProjectReleaseRequest.java:37-38·56·66`, `ProjectReviewRequest.java:43`, `ProjectRequestCommand.java:36`(`state` `String`), `studio/db/V26`(CHECK 0개). 옛 requirements R7.3은 `WITHDRAWN`을 빠뜨렸다(이 초안에서 고침).
  - 목표: design 데이터 모델의 enum(`ReviewRequestStatus`·`ReleaseRequestStatus`·`CommandState`·`DeniedTargetStatus`)과 CHECK·종류별 상태 CHECK(ST-I15). 새 이행 파일, 적용 전 위반 행 수 조회.
  - 영향: 돈 없음. 잘못된 값이면 활성 자리 생성 열(`status='REQUESTED'`)이 조용히 빠뜨려 자리 1건 규칙이 깨진다.
  - 재현: 이행 테스트에서 `UPDATE studio_release_request SET status='REQUESTD'` 성공(지금) → CHECK 뒤 실패.

- [ ] **ST-T3. 대상당 `PENDING` 철회 1건을 DB가 지키지 않는다**
  - 어디서: `studio:core/service/project/RequestProjectWithdrawal.java:52-53`(코드 검사만).
  - 목표: ST-I8, `pending_target` 생성 열 + `uk_studio_command_pending_target`.
  - 영향: 돈 없음. Project 잠금을 우회하는 경로(이행·수동 수정)에서 같은 대상에 철회 두 건이 나가면 Review·Catalog가 두 번째를 다른 `withdrawalId`로 받는다.
  - 재현: 같은 `target_request_id`의 `PENDING` 행 두 개 INSERT 실패.

- [ ] **ST-T6. 금액·버전·참조 제약이 DB에 없다**
  - 어디서: `studio/db/V23__simple_projects.sql`(`price`·`content_version`·`file_size`·`checksum` CHECK 없음, `current_build_id` FK 없음, `project_publication`에 `project_id` FK 없음), `studio:core/domain/project/Project.java:102-114`(가격 검사 없음, DTO `UpdateStorePageRequest`의 `@PositiveOrZero`만).
  - 목표: design 데이터 모델의 CHECK·복합 FK(ST-I3·ST-I11·ST-I12·ST-I13), 도메인 `StorePage`에서 가격 ≥ 0 검사.
  - 영향: **가격(간접 돈)** — 음수 가격이 DB·스냅샷에 들어가면 Catalog·Order가 그 값을 받는다(받는 쪽 가드 여부는 각 모듈 스펙). 다른 버전 승인을 가리키는 출시 행을 DB가 막지 않는다(RI-2의 Studio 쪽 방어).
  - 재현: `price=-1` INSERT 실패, 다른 `content_version`의 `review_request_id`로 출시 행 INSERT 실패.

- [ ] **ST-T9. 출시 거절 사유가 자유 문자열이다**
  - 어디서: `ProjectReleaseRequest.java:139-140`(`failureReason` 2000자), `studio:core/domain/release/ProjectReleaseResult.java:107-108`, payload `ProjectReleaseRejectedEvent.reason`(`String`).
  - 목표: design `reject_reason` — Catalog `ReleaseRejectReason` 값만(플랫폼 design "`ProjectReleaseRejected.reason`은 enum으로 고정"). 값 목록은 Catalog 모듈 스펙이 정본(notes F3).
  - 영향: 돈 없음. 화면이 사유 문자열을 분기에 쓰면 Catalog 문구 변경에 깨진다.
  - 재현: 목록 밖 사유의 거절 결과가 영구 실패.

- [ ] **ST-T7. 요청 행의 `request_event_id`가 NULL을 허용하고 대조가 NULL을 통과시킨다**
  - 어디서: `studio/db/V23`(이행 행 NULL), `studio:core/service/project/RecordProjectWithdrawalResult.java:58`(`originalEventId != null &&`).
  - 목표: ST-I16, NOT NULL. 보존할 데이터가 없는 개발 환경이라 새 계약으로 통일한다([결정 74](../../../docs/decisions.md)). 이행 전 NULL 행 수를 세고 0이 아니면 이행을 멈춘다.
  - 영향: 돈 없음. 원요청 이벤트 ID 없는 행에는 어떤 철회 결과든 대상 대조를 통과한다.
  - 재현: `request_event_id` NULL 행에 다른 `requestEventId`의 철회 결과 → 지금 기록 → 목표 영구 실패.

- [ ] **ST-T14. 현행 이벤트 이름이 문자열 리터럴이고 도메인에까지 있다** (플랫폼 [T26](../platform/tasks.md))
  - 어디서: `ProjectResultListener.java:33-48`(`isType("…")` 6곳), `studio:core/domain/project/WithdrawalResolution.java:21·24`(도메인 record가 `eventType` 문자열을 비교).
  - 목표: [P2.1-4](../platform/requirements.md#p21-봉투). `EventType` 상수 참조, 종류 판정은 리스너가 하고 도메인 입력은 종류 enum만 받는다. T21(ST-T1)과 같은 PR이면 새 이름으로 바로 올린다.
  - 영향: 한쪽만 이름을 바꾸면 리스너가 조용히 건너뛴다.
  - 재현: 소스 검사 `isType("` 0건.

- [ ] **ST-T5. 도메인 모델이 공통 이벤트 payload를 입력·출력으로 갖는다** (AGENTS.md 3절)
  - 어디서: `ProjectRequestCommand.java:4-5·89-91·93`(`ProjectWithdrawalRequest` 생성, `ProjectWithdrawalResult` 입력), `WithdrawalResolution.java`(core 도메인 record가 `ProjectWithdrawalResult`를 감쌈), `RecordProjectWithdrawalResult.java:34`.
  - 목표: design 아키텍처 위치 — 리스너 `*Mapper.from`이 Studio 내부 입력(`withdrawalId`·대상 ID·`projectId`·`contentVersion`·`productCode`·결과 종류 enum·`targetStatus`·`withdrawnBy`·확정 시각·`requestEventId`)으로 바꾸고, 철회 요청 payload는 서비스가 만든다. ArchUnit에 `core.domain → common.kafka.contract.payload` 금지 규칙.
  - 영향: 돈 없음. 이벤트 모양 변경(ST-T1)이 도메인 수정을 부른다.
  - 재현: ArchUnit 규칙 추가 시 Red.

- [ ] **ST-T15. 정방향 대기 게이지가 없다** ([결정 158](../../../docs/decisions.md#forward-gap-counting), 플랫폼 [T6](../platform/tasks.md))
  - 어디서: Studio에 결과 없는 `REQUESTED` 요청·`PENDING` 명령 지표 없음(`studio:` 전체에 Micrometer 게이지 없음).
  - 목표: R10, design "정방향 응답 쌍에서 Studio가 세는 것". 심사 요청 쌍의 판정 방법은 [결정 필요 M-ST-3].
  - 영향: **보유권(간접)** — 출시 결과가 실패 보관 표에 묻히면 창작자 화면에서만 드러나고, 그동안 다음 출시가 409.
  - 재현: Catalog 소비자를 멈춘 채 출시 요청 → 게이지 건수 1·최장 대기 증가.

- [ ] **ST-T10. 재발행·재투입 경로가 기본으로 꺼져 있고 재발행 이행이 없다** (결정 155·160·161, 플랫폼 [T2](../platform/tasks.md)·[T7](../platform/tasks.md))
  - 어디서: `apps/studio/src/main/resources/application.yml`(`stove.messaging.ops.enabled: false`, `republish-owner` 없음), `outbox_republish` 이행 없음, `common/messaging/.../ops/OutboxRepublishTargets.java:27-37`(Studio 이벤트 없음).
  - 목표: R11. Studio 발행 4종 재발행, 실패 보관 표·키 정지. 회수 정렬 기준이 플랫폼에서 둘로 적혀 있다(**1회차 지적 B1, 판정 전**) — 공통 메시징이 정한 기준을 그대로 쓴다.
  - 영향: **보유권(간접)** — `ProjectReleaseRequested`가 브로커에서 사라지면 Studio 출시 자리가 영구히 차고 되살릴 방법이 없다.
  - 재현: Studio `SENT` 행 재발행 API → 지금 비활성 404 → 목표 같은 `eventId`로 `PENDING`.

- [ ] **ST-T12. 저장소 HEAD 호출이 DB 트랜잭션·Project 잠금 안에 있다**
  - 어디서: `studio:core/service/review/RequestProjectReview.java:64`, `studio:core/service/release/RequestProjectRelease.java:59-60`, `studio:core/service/project/UpdateStorePage.java:29`(클래스 `@Transactional`).
  - 목표: 플랫폼 P1.2-4와 P3.4-1의 판정 뒤 정한다(notes F2). 판정 전에는 바꾸지 않는다.
  - 영향: 돈 없음. 저장소 지연이 Project 잠금·DB 연결을 붙잡아 같은 Project의 결과 기록까지 늦춘다.
  - 재현: 저장소 HEAD를 5초 지연시킨 픽스처에서 같은 Project 결과 소비가 함께 지연되는지.

- [ ] **ST-T11. 프로젝트 응답이 JPA 엔티티를 그대로 직렬화한다**
  - 어디서: `studio:api/controller/dto/ProjectResponse.java:14-18`(`ProjectReviewRequest`·`ProjectReleaseRequest` 엔티티 필드).
  - 목표: design API 계약 — 요청 응답 전용 DTO, 스냅샷 원문·`request_event_id` 비노출. OpenAPI 스냅샷(`studio.json`) 갱신.
  - 영향: 돈 없음. 칸 이름 변경(ST-T4·ST-T9)이 바로 API 변경이 되고 내부 식별자가 노출된다.
  - 재현: `GET /projects/{id}` 응답에 `snapshot` 키가 없음을 단언(지금 있음).

- [ ] **ST-T13. 운영 설정에 로컬 기본값(자격 증명·주소)이 있다** (원칙 ⑦)
  - 어디서: `application.yml`(`DB_PASSWORD:stove1234`, `STORAGE_ACCESS_KEY:stove`·`STORAGE_SECRET_KEY:stove1234`, `STUDIO_ASSET_PUBLIC_BASE_URL:http://localhost:8080`), `studio:config/StudioAssetProperties.java`(기본 URL).
  - 목표: 운영 프로필에서 값이 없으면 기동 실패. 로컬 값은 로컬 프로필·Compose에만. 다른 모듈도 같은 모양이라 공통 규칙으로 정한다(notes F5).
  - 영향: 돈 없음. 설정 누락이 조용히 로컬 값으로 기동한다.
  - 재현: 운영 프로필에 키 없이 기동 → 실패 단언.

## 진행 중 / 남음

- [ ] [결정 필요 M-ST-1] 심사·출시가 오래 걸릴 때의 창작자 통지 계약(심사 진행 중 보류 필요성 포함) — Review·Catalog 경계와 함께. 플랫폼 실패 표 S1·S4 "상대 모듈 중단" 행이 이 항목을 가리킨다.
- [ ] [결정 필요 M-ST-2] Studio 검색·상태 필터, 목록 요약/상세 분리.
- [ ] [결정 필요 M-ST-3] 심사 요청 쌍의 정방향 누락 판정(사람 판정 대기와 전달 누락의 구별) — ST-T15의 심사 부분.
- [ ] 이행 파일 V28 이후(목표 CHECK·FK·칸 이름) 한 묶음과 적용 전 위반 행 조회 — MC-ST3·ST-T3·ST-T4·ST-T6·ST-T7·ST-T9·ST-T1 칸을 함께.
- [ ] 이벤트 분리 이행 순서(P2.7-4): ① Studio가 옛 `…Resolved`와 새 4종·`ProjectReleaseClosed`를 모두 받는 판(ST-T1·ST-T2·MC-ST1·ST-T14) ② Review·Catalog 발행 전환 ③ 옛 타입이 Outbox·실패 보관 표에서 0건 뒤 옛 처리 제거.

## 범위 밖(후속)

- 심사 판정·공개 확정·공개 상품/상점 진열·파일 배포(Review·Catalog·Store·Download).
- 예약·출시 채널·승격·롤백, CI·테스터·웹훅, 분할 업로드 세션.
- 공개 확정 전 철회의 시간 보장(필요하면 예약 시각·유예·별도 최종 공개 행동 중 선택)·공개 후 되돌리기.
- 대표 장르·선택 순위·장르 계층, 동일 자료 차단용 내용 해시·고비용 검증 캐시.
- DLC 모델(별도 권한이 필요하면 독립 상품·Project와 본편 관계를 추천했으나 미결정)·선물하기·구매와 사용권 등록 분리.
- V1~V22 옛 표(`game_project`·`workspace` 등) 정리.
