# 작업: Review

## 완료 (검증 결과는 [근거 색인](../../../docs/evidence.md))

- [x] 요청 단위 `ProjectReview`(V10)·불변 스냅샷·`review-project` 수신·Inbox 중복 제거 — 2026-10-14
- [x] 5개 업무 API(목록·상세·빌드 열람·승인·반려), REVIEWER·ADMIN 권한, 판정 멱등·409·행 잠금, 결과 이벤트 발행 — 2026-10-14
- [x] 구 심사 흐름·여섯 유형·등급·배정·체크리스트·SLA·이의 제기 제거, V1~V9·역사 자료 보존 — 2026-10-14
- [x] `WITHDRAWN`·철회 감사 필드(V11), 원요청보다 먼저 온 철회 보존, 최종 판정 전 철회·먼저 커밋된 결과 유지(결정 53·60), 철회 후 동일 자료 재신청(결정 59) — 2026-11-03

## 코드와 맞출 것

형식: 무엇이 · 어디서(`파일:줄`) · 목표 · 영향 · 재현 테스트. 경로 줄임 `review:` = `apps/review/src/main/java/com/stove/review/`. 줄 번호는 2026-11-10 `docs/spec-foundation` 기준. 돈·보유권 영향 순.

- [ ] **RV-T4. 승인 이벤트가 Catalog가 대조할 스냅샷 값을 싣지 않는다** ([결정 167](../../../docs/decisions.md#catalog-verifies-approval), 플랫폼 T16·MC-C2의 Review 쪽)
  - 어디서: `common/kafka/.../contract/payload/ProjectReviewApprovedEvent.java`(칸: `reviewRequestId`·`projectId`·`contentVersion`·`productCode`·`actor`뿐), `review:core/service/DecideProjectReview.java:28-33`.
  - 목표: R9.1·R9.2. 승인 이벤트에 Review가 저장한 `ProjectSnapshot` 전체를 `snapshot` 칸으로 싣는다([결정 181](../../../docs/decisions.md#approval-snapshot-payload)). 같은 `eventType`의 새 `schemaVersion`, P2.7-5 순서(Studio·Catalog가 두 버전을 읽는 판 → Review 발행 전환). 이미 `APPROVED`인 심사의 승인 사실은 이행하지 않는다([결정 182](../../../docs/decisions.md#no-approval-migration), RV-T13).
  - 영향: **돈·보유권** — 이 칸이 없으면 Catalog가 결정 167 대조를 할 수 없어 RI-2 가드(T16)가 성립하지 않는다. Studio 결함·위조된 출시 요청·수동 수정된 Studio `APPROVED`로 승인 안 된 자료가 공개·판매되고 그 자료로 보유권이 지급된다.
  - 재현: 승인 API 호출 뒤 `outbox_event.payload`에 저장 스냅샷과 같은 값(또는 같은 지문)이 있는지 단언 — 지금 Red.

- [x] **RV-T13. 결정 167 이전에 승인된 심사의 승인 사실을 Catalog가 받을 길이 없다** — [결정 182](../../../docs/decisions.md#no-approval-migration): 이행하지 않음(작업 없음). 아래 기록은 판단 근거로 남긴다.
  - 어디서: `stove_review.project_review`(`status='APPROVED'` 기존 행), 재발행 경로는 같은 `eventId`·본문만 다시 낸다(P2.2-7)라 새 칸을 실을 수 없다.
  - 목표: R9.4. 정해진 이행 방법으로 기존 승인 사실을 Catalog `catalog-review-approval`에 보관시킨다. 정해지기 전에는 그 승인을 근거로 한 출시가 Catalog 승인 대기에 머문다.
  - 영향: **판매(출시 정지)** — 위험한 쪽은 아니지만(공개 안 함), 이미 승인된 자료의 새 출시·재출시(결정 58)가 Catalog 승인 대기에서 멈춘다.
  - 재현: 이행 전 `APPROVED` 행 하나로 Studio가 출시 요청 → Catalog 승인 대기 게이지 1이 계속 남는지.

- [ ] **RV-T2. 철회 결과 이벤트 하나가 "철회됨"과 "철회 거절"을 겸한다** (플랫폼 T21의 Review 쪽, [결정 176](../../../docs/decisions.md#withdrawal-result-events))
  - 어디서: `review:core/domain/ReviewWithdrawal.java:56-69`(`status` 문자열, `actor`·`feedback`·`finalizedAt`이 철회자/판정자 번갈아), `review:core/service/ReceiveProjectReview.java:51-55`, `ReceiveReviewWithdrawal.java:45-46`, payload `ProjectReviewWithdrawalResolvedEvent`·`ProjectWithdrawalResult`.
  - 목표: R6.6·R6.9, design "발행" 표, RV-I13. 해결 결과를 `ProjectReviewWithdrawn` / `ProjectReviewWithdrawalDenied(targetStatus)`로 나눠 내고, Denied는 `actor`·`feedback`을 싣지 않는다. P2.7-4 배포 순서(Studio가 세 타입을 모두 받는 판 → Review 전환 → 옛 타입이 Outbox·실패 보관 표·토픽 보존에서 0건 확인 뒤 옛 처리 삭제).
  - 영향: 돈 없음. Studio 창작자 영수증의 `finalActor`·`finalFeedback`이 철회 결과와 반려 사유를 오가며 잘못 표시된다.
  - 재현: 반려 뒤 철회 → 결과가 `ProjectReviewWithdrawalDenied`이고 `targetStatus=REJECTED`, 본문에 `actor`·`feedback` 칸이 없음을 단언(지금 Red).

- [ ] **MC-R1. `project_review.status`에 DB CHECK가 없다** (module-carryover, 출시 C-4 Review 부분)
  - 어디서: Java `ReviewStatus` enum(`review:core/domain/ProjectReview.java:49-51`), `review/db/V10`·`V11`에 CHECK 0개.
  - 목표: design "데이터 모델" `chk_project_review_status`, RV-I4.
  - 영향: 돈 없음. 허용 밖 값(예: 수동 수정 `'REQUESTD'`)이면 생성 열 `active_project_id`가 NULL이 되어 활성 자리 유일 키가 조용히 빠지고, 판정 API는 그 행을 최종으로 보고 409를 낸다.
  - 재현: 이행 테스트에서 `UPDATE project_review SET status='REQUESTD'`가 실패함을 단언(지금 성공).

- [ ] **RV-T1. 상태별 칸 조합과 `review_withdrawal.resolved`를 DB가 지키지 않는다**
  - 어디서: `review/db/V10`(`actor`·`decided_at`·`feedback` NULL 허용, 조건 없음), `review/db/V11`(`withdrawn_*` NULL 허용, `resolved BOOLEAN`), 지키는 곳은 `ProjectReview.decide`·`withdraw` 코드뿐.
  - 목표: design "데이터 모델" `chk_project_review_decision`·`_feedback`·`_withdrawn`, `chk_review_withdrawal_resolved`. 이행 전 위반 행 0 확인(V11 전환과 같은 방식). RV-I5.
  - 영향: 돈 없음. 수동 수정·결함으로 `APPROVED`인데 `actor` NULL, `WITHDRAWN`인데 `feedback` 있음 같은 행이 생겨도 막지 못하고, 그 행으로 낸 결과 이벤트가 칸 없는 사실이 된다.
  - 재현: `status='APPROVED', actor=NULL` INSERT가 실패함을 단언(지금 성공).

- [ ] **RV-T5. 재시도로 바뀌지 않는 수신 실패를 4회 재시도한다** (플랫폼 T20의 Review 쪽)
  - 어디서: `review:api/listener/ReviewSubmissionMapper.java:14-18`·`ReviewWithdrawalMapper.java:15-19`(`BusinessException(INVALID_REQUEST)`), `review:core/service/ReceiveProjectReview.java:41`(스냅샷 충돌 `CONFLICT`), `ReceiveReviewWithdrawal.java:40`, `ReviewWithdrawal.java:47-48·60`, 활성 유일 키 위반(`DataIntegrityViolationException`, 분류 코드 없음). 공통 핸들러는 모두 재시도(`common/kafka/.../KafkaConsumerAutoConfiguration.java:37-43`).
  - 목표: R2.5, design 상태×트리거 표의 "영구 실패" 칸. 위 실패를 공통 영구 실패 예외로 던져 재시도 없이 실패 보관·키 정지.
  - 영향: 돈 없음. 계약 위반 한 건이 `review-project` 파티션을 7초 이상 멈춰 같은 상품의 뒤 철회 요청을 늦춘다. 활성 키 위반이 일시 오류인지 계약 위반인지 운영자가 구분하지 못한다.
  - 재현: 같은 `reviewRequestId`·다른 스냅샷 레코드를 넣고 실패 보관까지 처리 시도 1회 단언.

- [ ] **RV-T7. 원요청 없는 철회를 세지 않고, 대상이 다른 보관 철회가 원요청 접수를 막는다** ([P3.2-7](../platform/requirements.md#p32-심사-요청철회-studio--review), 결정 158, [결정 필요 M-RV-3])
  - 어디서: 게이지 없음(`review:` 전체에 `MeterRegistry` 사용 없음), `review:core/service/ReceiveProjectReview.java:51-54`(보관 철회의 `resolve`가 `CONFLICT`를 던지면 원요청 접수 전체가 롤백), `ReviewWithdrawal.java:57-60`.
  - 목표: R10.1 게이지(건수·최장 대기, Review 시계 `created_at` 기준, 이름 예 `stove_review_withdrawals_without_request`·`…_oldest_seconds`). 대상 불일치 처리는 M-RV-3 결과대로.
  - 영향: 돈 없음. 원요청이 끝내 안 오면 Studio 철회 명령이 `PENDING`, 활성 심사 자리가 점유된 채 아무도 모른다. 대상 불일치 철회 하나가 원요청을 실패 보관에 묶고 그 상품 키를 정지시킨다(결정 155).
  - 재현: 철회만 넣고 게이지 1·대기 시간 증가 단언. 대상 `contentVersion`이 다른 철회 → 원요청 순서로 넣고 원요청이 접수되지 않음을 확인(지금 동작 고정) → M-RV-3 결과로 바꿈.

- [ ] **RV-T8. 실패 보관 표·키 정지·재발행 경로가 없고 운영 API가 기본 꺼져 있다** (플랫폼 T2·T7, [결정 155](../../../docs/decisions.md#key-stop-on-dead)·[160](../../../docs/decisions.md#dead-letter-store)·[161](../../../docs/decisions.md#republish-all-current))
  - 어디서: `review/db/`에 실패 보관 표·`outbox_republish` 없음, `apps/review/src/main/resources/application.yml:60-63`(`stove.messaging.ops.enabled: false`), 재발행 허용 목록 `common/messaging/.../ops/OutboxRepublishTargets.java:27-37`에 Review 이벤트 없음.
  - 목표: R10.2·R10.3. common messaging 설계의 표·API를 Review에 이행, 허용 목록에 R8.2의 현행 이벤트.
  - 영향: 돈 없음(간접: 승인 사실이 Catalog에 닿지 않으면 출시 정지). DLT 보존 기간이 지나면 Review 결과 이벤트를 되살릴 수단이 없다.
  - 재현: `SENT`인 `ProjectReviewApproved` 행을 재발행 API로 되돌리면 지금 400 → 이행 뒤 같은 `eventId`로 다시 나감.

- [ ] **RV-T6. core가 저장소 경로 형식(`s3://`)을 검사한다** (원칙 ②, 결정 140)
  - 어디서: `review:core/domain/ReviewSubmission.java:23`(`s.storagePath().startsWith("s3://")`).
  - 목표: core는 `storagePath`가 비어 있지 않은 2000자 이하 문자열인지만 본다. 형식·버킷 확인은 `S3ReviewBuildStorage`가 한다(design adapter 메모). 플랫폼 T28 규칙이 잡을 대상.
  - 영향: 돈 없음. 저장소 공급자를 바꾸면 core 수정이 필요하다.
  - 재현: ArchUnit/소스 검사 — `core` 패키지에 `"s3://"` 리터럴 0건.

- [ ] **RV-T11. 이벤트 타입이 문자열 리터럴이고, `schemaVersion`을 보지 않고, 모르는 타입을 지표 없이 건너뛴다** (플랫폼 T25·T26·T31, 결정 170)
  - 어디서: `review:api/listener/StudioEventListener.java:27·30`(`isType("…")`, else 분기 없음), `review:core/domain/ReviewWithdrawalValues.java:30`(`"ProjectReviewWithdrawalRequested"` 리터럴), payload `eventType()` 리터럴, 헤더 `occurredAt` = Outbox 적재 시각(공통).
  - 목표: R1.5·R8.2·R8.7. `EventType` 상수, (`eventType`, `schemaVersion`)으로 Mapper 선택, 건너뜀 (그룹, 타입) 지표, 본문·헤더 `occurredAt` = 사실 시각.
  - 영향: 돈 없음. Studio가 이름을 바꾸면 Review가 조용히 요청을 버린다.
  - 재현: 모르는 타입 1건 소비 뒤 지표 1, 소스 검사 `isType("` 0건.

- [ ] **RV-T3. 결과 이벤트의 연결 값이 저장 행 값임을 단언하는 테스트가 없다** (RV-I14)
  - 어디서: `ProjectReviewIntegrationTest`(판정 이벤트 본문 칸 대조 단언 확인 못 함).
  - 목표: 판정·철회 결과 이벤트의 `reviewRequestId`·`projectId`·`contentVersion`·`productCode`·`requestEventId`가 `project_review` 행과 같음을 단언.
  - 영향: 돈 없음. RI-1·RI-2 대조의 입력이 틀려도 테스트가 모른다.
  - 재현: 위 단언 추가.

- [ ] **RV-T9. 옛 Review 계약 상수 제거 근거** (플랫폼 T30, [결정 172](../../../docs/decisions.md#remove-legacy-contracts))
  - 어디서: `common/kafka/.../contract/EventType.java:33-41`(`REVIEW_APPROVED`·`REVIEW_REJECTED`·`SUBMISSION_REVIEW_APPROVED`·`REVIEW_CHANGES_REQUESTED`·`REVIEW_APPEALED`), Review의 V1~V9 시절 Outbox 행.
  - 목표: R8.6. 제거 PR에 `SELECT event_type, status, COUNT(*) FROM stove_review.outbox_event WHERE event_type IN ('ReviewApproved','ReviewRejected','SubmissionReviewApproved','ReviewChangesRequested','ReviewAppealed') AND status IN ('PENDING','DEAD') GROUP BY 1,2` 0행과 Review 실패 보관 표 0행을 첨부.
  - 영향: 돈 없음.
  - 재현: 위 SQL.

- [ ] **RV-T10. 운영 설정에 로컬 개발 기본값이 들어 있다** (원칙 ⑦, 확인 필요)
  - 어디서: `apps/review/src/main/resources/application.yml:13-14·22-24·65-68`(JWT·DB·저장소 주소·버킷 기본값, `DB_PASSWORD:stove1234`), `review:config/ReviewStorageProperties.java:17-19`(`region`·`bucket` 기본값).
  - 목표: 운영 프로필에서 이 값들이 빠지면 기동 실패(읽기 키처럼). 로컬 기본값은 개발 프로필로. 다른 모듈과 같은 규칙이어야 하므로 플랫폼과 맞출 경계(notes F).
  - 영향: 돈 없음. 운영에서 값을 빠뜨리면 로컬 주소로 기동해 늦게 실패한다.
  - 재현: 운영 프로필로 해당 환경 변수 없이 기동 → 실패 단언.

## 진행 중 / 남음

- [x] 승인 이벤트의 대조 값 형식 — [결정 181](../../../docs/decisions.md#approval-snapshot-payload): 스냅샷 전체 → RV-T4.
- [x] 결정 167 이전 승인 사실의 Catalog 이행 — [결정 182](../../../docs/decisions.md#no-approval-migration): 이행하지 않음 → RV-T13 닫음.
- [ ] [결정 필요 M-RV-3] 원요청보다 먼저 보관한 철회의 대상이 원요청과 다를 때 → RV-T7.
- [ ] [결정 필요 M-RV-4] 종료된 심사의 빌드 열람 URL 발급 범위(R3.8).
- [ ] [결정 필요] 심사 목록/상태 조회의 사용성 개선 — G4 ([일반화 계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md) 4절 Review 행).
- [ ] [결정 필요] 진행 중 요청 철회/보류 시 창작자 통지 계약 — G4(플랫폼 흐름 1 S1 "상대 모듈 중단" 행의 통지 공백과 같은 항목).

## 범위 밖(후속)

- 자동 심사·다단계 관문.
- 정교한 담당 배정/SLA.
- 승인·반려를 뒤집는 재판정 예외 API, 관리자 강제 철회.
