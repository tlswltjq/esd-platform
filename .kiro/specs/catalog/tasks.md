# 작업: Catalog

> 상태: 작성 중(결정 150·178). 목표 계약은 [requirements.md](requirements.md)·[design.md](design.md). 모듈을 넘는 차이는 [플랫폼 tasks](../platform/tasks.md)의 `T`번호, 이 모듈 안에서 끝나는 차이는 아래 `MC-C*`(이월)·`CA-T*`(새로 찾음). 경로 줄임 `catalog:` = `apps/catalog/src/main/java/com/stove/catalog/`, `catalog/db/Vn` = Flyway. 줄 번호는 2026-11-10 `spec-foundation` 작업 폴더 기준. 순서는 돈·보유권 영향 순.

## 완료 (검증 결과는 [근거 색인](../../../docs/evidence.md))

- [x] 출시 요청 처리: 입력 계약 검증·최초 공개·상위 버전 교체·하위 버전 거절(STALE_CONTENT_VERSION), 불변 스냅샷 — 2026-10-15
- [x] 중복·동시성: Inbox 중복 제거, 동일 요청 재전달 결과 유지, 식별 충돌·상충 스냅샷 DLT, 행 잠금·유일 제약·전체 재시도 — 2026-10-15
- [x] 공개 조회: `GET /api/v1/products`(page≤100·안정 정렬), `by-code/{code}` 상세, 익명 GET, 내부 필드 미노출·400/404 — 2026-10-15
- [x] 이벤트: ProjectReleaseRequested 수신·Completed/Rejected 발행, requestEventId 연결, consumer group `catalog-project-release`(earliest) — 2026-10-15
- [x] V11 catalog_product·catalog_release·인덱스 추가, V1~V10 체크섬 유지, 구 업무 코드(프로모션·견적·캐시·재색인·Workspace 연동) 제거 — 2026-10-15
- [x] MVP 독립 검증: 실제 5개 앱 로그인·업로드·심사·출시·공개 조회·Studio 완료 반영, 릴레이 중단·재시작·Kafka 복구 (관련 481건) — 2026-10-15
- [x] 판매 상태: 판매자 ON_SALE/STOPPED·ADMIN 독립 제한·BLOCKED, catalog_sale_change 감사, V12 이행, 공개 사본 saleStatus·catalogVersion, Order 새 주문/승인 접수 경계 (관련 1,730건) — 2026-11-02
- [x] 다운로드 제한: ADMIN GET/PUT·202 접수·필수 사유, catalog_download_change, V13 이행, 저장소 태그 PENDING/APPLIED/FAILED 수렴, 읽기 계정 IAM 거절 검증 (관련 348건) — 2026-11-02
- [x] 검색·목록 노출: listingPreference(AUTO/LISTED/UNLISTED)·platformListingHidden, 최종 listed 규칙, catalog_listing_change, V14 이행, 공개 사본 listed·내부 전체 동기화 키 (관련 548건) — 2026-11-02
- [x] 공개 상세 B: 최종 listed=false의 공개 상세·새 주문 404, ProductPublicAccess 일원화, 내부 order-products 근거(publicDetailAllowed), 전용 Order 서비스 키 (관련 768건) — 2026-11-02
- [x] 출시 철회 조정과 내구 공개 작업(V15·자동 재시도·ADMIN 작업 조회): ProjectReleaseWithdrawalRequested 수신·Resolved 발행, 공개 확정과 같은 종료 경계, 선확정 결과 유지, 유예시간 없음 — 2026-11-03 (requirements R9·R10)
- [x] genres(1~3개) 공개 응답·공개 이벤트·내부 정본 포함, 복수 장르 AND 검색 연계 — 2026-11-03 (G4 장르 분류)

## 코드와 맞출 것

형식: 무엇이 · 어디서 · 목표 · 영향 · 재현 테스트.

### MC-C2. 공개를 확정하기 전에 Review 승인을 대조하지 않는다 (결정 167, 플랫폼 T16)
- 무엇이: `reviewRequestId`를 저장만 하고 Review 승인 사실을 받지도 대조하지도 않는다. 승인 보관 표·승인 잠금·`AWAITING_APPROVAL` 상태·`catalog-review-approval` 소비 그룹·`APPROVAL_MISMATCH` 사유·승인 대기 게이지가 없다.
- 어디서: `catalog:core/service/ReceiveProjectRelease.java:38-63`(승인 확인 없이 `PENDING` 작업 저장), `PublishProjectRelease.java:50-170`(공개 트랜잭션에 승인 재확인 없음), `catalog:api/listener/`(Review 리스너 없음), 이전 설계 "Studio 명령을 신뢰"(`.kiro/specs/catalog/design.md:8·20`).
- 목표: R11 전체, design 표 1(A=·A≠ 열)·표 2, 데이터 모델 `catalog_review_gate`·`catalog_review_approval`, CI-8·CI-11. 승인이 철회·거절·종결로 끝난 요청을 열지 않는 조건 포함(1회차 지적 B3, 판정 전). 승인 본문의 스냅샷 값은 `snapshot`(Review 보관 `ProjectSnapshot` 전체)이고 레코드 동등으로 비교한다(결정 181, Review RV-T4 먼저 배포).
- 영향: **승인되지 않은 자료가 공개·판매되고 그 자료로 보유권이 지급될 수 있다**(Studio 결함·생산 권한 오남용·Studio DB 수정, 결정 167 문제).
- 재현: ① 승인 보관 없이 출시 요청 → 작업이 `AWAITING_APPROVAL`이고 작업자가 공개하지 않음(지금 Red: 공개됨) ② 승인 값과 다른 `contentVersion`의 출시 → `REJECTED(APPROVAL_MISMATCH)` ③ 승인 대기 중 철회 → `WITHDRAWN` → 뒤늦은 승인 → 작업이 열리지 않음(B3) ④ 출시·승인 동시 도착 두 순서 모두 같은 결과.

### MC-C3. 재시도로 풀리지 않는 공개 실패도 무한 재시도하고, 정지·재개·종결·경보가 없다 (결정 168, 플랫폼 T23)
- 무엇이: 모든 `RuntimeException`을 같은 재시도로 처리해 계약 위반 작업이 60초마다 영원히 `PENDING`이다. `BLOCKED` 상태·분류 코드·운영 재개/종결 API·`catalog_release` `CLOSED`·`ProjectReleaseClosed`·경보가 없다.
- 어디서: `catalog:core/service/PublishQueuedReleases.java:44-48`, `catalog:core/domain/CatalogPublicationJob.java:68-79`(상한·종료 상태 없음), `catalog:api/controller/PublicationJobController.java:22-31`(조회만), `infra/prometheus/alerts.yml`(Catalog 규칙 없음). 같은 무한 재시도에 빠지는 다른 원인: 소유자가 다른 선행 철회가 보관된 채 공개 작업이 돌면 `CatalogRelease.withdrawn`(`CatalogRelease.java:74-75`)이 `FORBIDDEN`을 던진다(CA-T4와 함께).
- 목표: R12 전체, design 표 1(`BLOCKED` 행·OR·OC 열)·`PENDING × J` 판정 표, 데이터 모델 `blocked_at`·`last_error_code`·`catalog_release_closure`·`catalog_publication_job_action`, CI-16. 분류는 플랫폼 T20의 공통 영구 실패 예외로.
- 영향: **그 Project의 출시 요청이 Studio `REQUESTED`로 남아 다음 출시가 모두 409 — 기존 보유자가 업데이트 빌드를 받지 못한다.** 돈 영향은 없다.
- 재현: 같은 Project·같은 버전에 다른 스냅샷을 공개 시점에 만들어(접수 뒤 다른 요청이 먼저 공개) 작업 실행 → 1회 실패 뒤 `BLOCKED`·경보 1건(지금 Red: `PENDING`·`attemptCount` 증가만). 재개 → `PENDING`, 종결 → `CLOSED`·`ProjectReleaseClosed` 1건·작업 `COMPLETED`.

### CA-T4. 원요청보다 먼저 보관된 철회가 대상과 맞지 않으면 원요청 접수 자체가 실패한다 [결정 필요 M-CA-3]
- 무엇이: 선행 철회는 대상 신원을 확인하지 못한 채 보관되고, 원요청 접수 트랜잭션의 `withdrawals.resume`이 `ReleaseWithdrawal.resolve`(`CONFLICT`) 또는 `CatalogRelease.withdrawn`(`FORBIDDEN`)을 던져 접수 전체가 롤백된다. 원요청 레코드가 재시도 뒤 실패 보관·키 정지로 가 같은 `productCode`의 뒤 레코드가 모두 멈춘다.
- 어디서: `catalog:core/service/ReceiveProjectRelease.java:62`, `ReceiveReleaseWithdrawal.java:59-64·75-77`, `catalog:core/domain/ReleaseWithdrawal.java:57-61`, `CatalogRelease.java:72-76`.
- 목표: R9.8, design 표 1 `NONE × W≠`·표 3. 처리 방식은 M-CA-3 결과(추천 A: 원요청은 정상 접수, 맞지 않는 철회는 적용하지 않고 `CONFLICTED`로 보관·1건 경보).
- 영향: **정상 출시 요청 하나가 Studio 쪽 계약 결함 하나로 그 상품의 모든 뒤 출시 처리를 멈춘다(보유자 업데이트 지연).**
- 재현: 소유자가 다른 철회를 먼저 넣고 원요청 → 지금은 원요청이 실패 보관으로 감(Red), 목표는 작업 생성 + 철회 `CONFLICTED`.

### CA-T2. 저장소 태그 적용을 상품 행 잠금을 쥔 DB 트랜잭션 안에서 부른다
- 무엇이: 공개본 수만큼 저장소 호출(호출당 최대 5~10초)을 상품 행 `FOR UPDATE` 안에서 한다. 그동안 같은 상품의 판매·노출·다운로드 명령과 공개 확정이 기다린다.
- 어디서: `catalog:core/service/ReconcileDownloadStorage.java:41-68`(주석 "Serialize desired-state changes ... and storage mutations").
- 목표: R6.4, design "adapter 메모" 저장소 적용 흐름(읽기 트랜잭션 → 트랜잭션 밖 호출 → 제한 버전 대조 후 기록, 표 6 K~), 플랫폼 P1.2-4.
- 영향: 돈 없음. 저장소 지연 때 관리자 판매 제한(`BLOCKED`) 명령이 늦어져 그 사이 새 주문·승인 접수가 열린다. 커넥션 점유.
- 재현: 저장소 호출을 지연시키는 대역으로 적용 작업 중 판매 제한 PUT의 대기 시간이 호출 시간 이상임을 단언(지금 통과) → 목표에서 즉시 커밋.

### CA-T5. 철회가 공개 전에 이기면 결과는 `WITHDRAWN`인데 작업은 `PENDING`으로 남는다
- 무엇이: 철회 경로가 `catalog_release`를 `WITHDRAWN`으로 INSERT하고 작업은 그대로 둔다. 다음 작업자 실행이 결과를 보고 `COMPLETED`로 바꿀 때까지 작업 `PENDING`과 결과가 함께 있다(숨은 조합 상태).
- 어디서: `catalog:core/service/ReceiveReleaseWithdrawal.java:66-79`, `PublishQueuedReleases.java:37-40`.
- 목표: CI-9, design 표 1 `W+` 열(결과와 작업 `COMPLETED`를 같은 트랜잭션에).
- 영향: 돈 없음. ADMIN 작업 목록(`PENDING`)에 끝난 요청이 보이고 대기 게이지가 부풀어진다.
- 재현: 작업자를 끈 채 접수 → 철회 → 작업 `state='COMPLETED'` 단언(지금 Red).

### CA-T1. 공개 작업자와 철회·접수의 잠금 순서가 다르다
- 무엇이: 작업자는 작업 행을 먼저 잠그고(`jobs.lock`) 그 안에서 요청 잠금을 잡는다. 접수·철회는 요청 잠금을 먼저 잡는다. CA-T5·MC-C3로 철회·종결 경로가 작업 행을 쓰게 되면 교착이 생길 수 있다.
- 어디서: `catalog:core/service/PublishQueuedReleases.java:35`, `PublishProjectRelease.java:51-52`, `ReceiveReleaseWithdrawal.java:44-45`.
- 목표: design 표 1 아래 "잠금 순서" — 승인 잠금 → 요청 잠금 → 작업 행 → 상품 행. 작업자는 후보를 잠금 없이 고르고 요청 잠금부터 잡는다.
- 영향: 돈 없음. 교착은 MySQL이 한쪽을 롤백해 재시도로 끝나지만 지연·경보 잡음.
- 재현: 작업자 실행과 철회를 같은 요청에 동시 실행해 교착 없음(테스트 전용 멈춤 지점, 결정 103).

### MC-C1. 출시 결과·사유·작업 상태가 문자열이고 CHECK가 없다
- 어디서: `catalog:core/domain/CatalogRelease.java:42·65-66·78`, `CatalogPublicationJob.java:30·46·63`, `catalog/db/V11`·`V15`(CHECK 0개).
- 목표: design 데이터 모델의 `chk_catalog_release_status`·`chk_catalog_release_reason`·`chk_catalog_release_rejected`·`chk_catalog_release_withdrawn`·`chk_publication_job_state`·`chk_publication_job_due`·`chk_publication_job_blocked`, Java enum `ReleaseOutcome`·`ReleaseRejectReason`·`PublicationJobState`.
- 영향: 돈 없음. 잘못된 값이면 작업 선택(`state='PENDING'`)에서 조용히 빠진다.
- 재현: 허용 밖 값 INSERT 실패(이행 테스트), 옛 행 이행 뒤 값 보존.

### CA-T3. "다운로드 제한을 한 번도 쓰지 않음"이 `download_restriction_version = 1`이라는 보조 칸으로 숨어 있다
- 어디서: `catalog:core/domain/CatalogProduct.java:146`(`downloadRestrictionVersion > 1`이면 재확인 예약), `CatalogProductRepository.java:17`(`downloadRestrictionVersion > 1` 조건으로 작업 선택), `catalog/db/V13`(`chk_download_work`, 기본 `APPLIED`).
- 목표: `download_storage_status`에 `NOT_REQUIRED`, design 표 6, `chk_download_not_required`·`chk_download_restricted_tracked`. 이행: 버전 1 상품을 `NOT_REQUIRED`로.
- 영향: 돈 없음. 상태 하나를 두 칸 조합으로 읽어 새 경로가 버전 조건을 빠뜨리면 미적용 상품을 `APPLIED`로 본다.
- 재현: 이행 테스트 — 버전 1 상품이 `NOT_REQUIRED`, 제한 뒤 `PENDING`, 허용 밖 조합 INSERT 실패.

### CA-T7. Catalog 지표·경보가 하나도 없다
- 어디서: `apps/catalog/src/main/java`에 `MeterRegistry` 사용 0, `infra/prometheus/alerts.yml`에 Catalog 공개 작업·저장소 적용 규칙 없음.
- 목표: R13(게이지 5종, `BLOCKED` 1건 경보), R11.8, 저장소 적용 실패 경보 기준은 M-CA-2. 플랫폼 T6·T23과 같은 PR이 맞다.
- 영향: 돈 없음. **다운로드 제한이 저장소에 적용되지 않은 채(`FAILED`) 오래 남아도 아무도 모른다**(이미 받은 URL이 계속 동작).
- 재현: 작업 `BLOCKED` 1건·저장소 `FAILED` 1건을 만들고 게이지 값 단언.

### CA-T11. 판매 상태·저장소 상태가 Java 문자열이고 `platform_restricted`에 CHECK가 없다
- 어디서: `catalog:core/domain/CatalogProduct.java:46-47·57·103·108·114·118·129`, `ListingPreference.java:6-9`(문자열 판매 상태 검사), `catalog/db/V12`(`platform_restricted` CHECK 없음, 다른 Boolean 칸은 V13·V14에 있음).
- 목표: enum `SellerSaleStatus {ON_SALE, STOPPED}`·`PublicSaleStatus {ON_SALE, STOPPED, BLOCKED}`·`DownloadStorageStatus`, `ck_platform_restricted`.
- 영향: 돈 없음.
- 재현: 컴파일 단위 — 문자열 상수 비교 제거, 이행 테스트로 CHECK.

### CA-T6. 최종 노출 규칙을 도메인과 목록 쿼리 두 곳에 적는다
- 어디서: `catalog:core/domain/CatalogProduct.java:65`·`ListingPreference.java:9`, `CatalogProductRepository.java:15`(JPQL로 같은 규칙).
- 목표: CI-15, R5.3 — 규칙 한 곳에서 쿼리 조건을 만들거나, 모든 상태 조합(표 4×표 5의 24개)에 대해 목록 포함 여부 = `isListed()`를 대조하는 테스트로 지킨다.
- 영향: 돈 없음. 한쪽만 바뀌면 목록과 상세가 어긋난다(주문은 상세 규칙을 따름).
- 재현: 24개 조합 매개변수 테스트.

### CA-T8. 공개본 불변·`catalogVersion` 단조성의 확인 테스트가 없다
- 목표: CI-2(결과 행 갱신 시도가 실패하거나 감지됨 — JPA 변경 메서드 없음을 ArchUnit으로, 또는 갱신 감지 테스트), CI-6(판매·노출·교체를 섞은 명령열 뒤 버전이 매번 증가하고 `contentVersion` 이상).
- 영향: 돈 없음.

### CA-T9. core 입력 검증이 공급자 저장 경로 스킴 `s3://`을 요구한다
- 어디서: `catalog:core/domain/ReleaseSubmission.java:24`.
- 목표: 원칙 ②·점검표 6 — core는 "비어 있지 않은 저장 경로(길이 상한)"만, 스킴 규칙은 저장소 adapter(`S3BuildDistribution`)가 적용 때 검사.
- 영향: 돈 없음.

### CA-T10. 작업자 끄기 설정을 테스트·검증 도구가 쓰고, 운영 용도가 적혀 있지 않다
- 어디서: `catalog:config/PublicationConfiguration.java:49`, `DownloadStorageConfiguration.java:30`, `apps/catalog/src/integrationTest/java/com/stove/catalog/CatalogIntegrationTestBase.java:12`, `tools/verification/verify-catalog.py`·`verify-g4-operations.py`.
- 목표: 원칙 ⑦ — 운영 용도(DB 전환 절차의 작업자 정지)를 design "설정"·사용 안내에 적어 운영 계약으로 두거나, 테스트는 결정 103의 테스트 전용 멈춤 지점으로 바꾼다. 기본값은 운영 값(true)이어야 한다(지금 충족).
- 영향: 돈 없음.

### CA-T12. ADMIN 작업 조회가 잘못된 페이지를 400 대신 조용히 고치고, `PENDING`만 보여 준다
- 어디서: `catalog:core/service/ReadPublicationJobs.java:20-21`(`Math.max/min`으로 보정, 상태 고정 `PENDING`).
- 목표: R10.4 — 상태 필터(`AWAITING_APPROVAL`·`PENDING`·`BLOCKED`), 잘못된 값 400.
- 영향: 돈 없음.

### CA-T13. 철회 수신 트랜잭션만 격리 수준이 기본값(REPEATABLE READ)이다
- 어디서: `catalog:core/service/ReceiveReleaseWithdrawal.java:30`(접수·공개는 `READ_COMMITTED`, `ReceiveProjectRelease.java:26`·`PublishProjectRelease.java:37`).
- 목표: 같은 요청 잠금을 쓰는 경로는 같은 격리 수준(`READ_COMMITTED`)으로, 잠금 뒤 읽기가 최신 커밋을 본다.
- 영향: 돈 없음. 갭 락 범위가 달라 경합 때 대기가 늘 수 있다.

### 플랫폼 T 항목 중 Catalog 코드가 바뀌는 것 (본문은 플랫폼 tasks)
- [ ] T16 — MC-C2와 같은 작업.
- [ ] T21 — `ProjectReleaseWithdrawalResolved`(`catalog:core/domain/ReleaseWithdrawal.java:63-67`, `actor="catalog"`)를 `ProjectReleaseWithdrawn`·`ProjectReleaseWithdrawalDenied(targetStatus)`로. `CLOSED` 값은 M-CA-5.
- [ ] T22 — `CatalogProductPublished`를 사실별 4종으로(`PublishProjectRelease.java:122-148`, `RecordCatalogSaleChange.java:20`, `ChangeCatalogListing.java:64`). P2.7-4 순서(Store 먼저).
- [ ] T23 — MC-C3와 같은 작업.
- [ ] T20 — 공통 영구 실패 예외. Catalog 매퍼·입력 검증(`BusinessException(INVALID_REQUEST)`)·`CONFLICT`를 영구 실패로 표시.
- [ ] T25·T26 — 출시 이벤트 문자열 리터럴(`StudioReleaseEventListener.java:27·30`, `PublishQueuedReleases.java:56`, `ReleaseWithdrawalValues.java:30`)을 `EventType` 상수로, `schemaVersion` 헤더.
- [ ] T31 — `StudioReleaseEventListener`가 모르는 타입을 지표 없이 건너뜀(`:27-33`).
- [ ] T7 — Catalog 재발행 경로(`outbox_republish`, `republish-owner`).

## 진행 중 / 남음

- [ ] MC-C2·MC-C3·CA-T4·CA-T5·CA-T1을 한 묶음으로: 출시 요청 상태 모델(design 표 1~3)을 한 PR에서 구현하고 표의 모든 칸을 통합 테스트로 덮는다. 순서: 상태 CHECK(MC-C1) → 잠금 순서(CA-T1) → 승인 대조(MC-C2) → 정지·종결(MC-C3) → 이벤트 분리(T21·T22). Studio의 `CLOSED` 끝 상태·`ProjectReleaseClosed` 소비와 Review 승인 본문 v2(`snapshot`, 결정 181·RV-T4)가 먼저 또는 같이 배포돼야 한다(notes F).
- [ ] [결정 필요] 공개본 변경 이력의 조회 계약·사용성 — G4 ([일반화 계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md) Catalog 행 "공개본 변경 이력")
- [ ] [결정 필요] 허용 지연 SLA와 실제 운영 경보·화면 — G4. 결정 158·159로 게이지는 R13에서 먼저 내고 경보 기준은 G6 측정 뒤.
- [ ] [결정 필요 M-CA-1] 승인 대기가 끝나지 않는 출시의 운영자 처리.
- [ ] [결정 필요 M-CA-2] 다운로드 제한 저장소 적용 실패의 경보 기준.
- [ ] [결정 필요 M-CA-3] 원요청과 맞지 않는 선행 철회의 처리(CA-T4).
- [x] 승인 사실의 스냅샷 값 전달 방식 — [결정 181](../../../docs/decisions.md#approval-snapshot-payload): 스냅샷 전체(MC-C2).
- [ ] [결정 필요 M-CA-5] 종결된 출시에 온 철회의 거절 결과(`targetStatus=CLOSED`).
- [ ] [외부 대기] 공유 환경의 정책/계정 준비와 실제 배포 — G4 이후
- [ ] [결정 필요] G4 전체 완료 판정(실제 운영 여정 전체) — G4. 최신 전체 여정은 2026-11-08 통과([근거 색인](../../../docs/evidence.md) G4 행). 남은 G4 `[결정 필요]` 4건(Catalog 변경 이력·허용 지연 SLA, Download 대기·정지 구분 안내·노출 중지 안내)을 G4 안에서 정할지 다음 단계로 넘길지 사용자가 정한 뒤 판정

## 범위 밖(후속)

- 예약 출시·롤백·채널·관리자 강제 공개.
- 할인·프로모션([Q13](../../../docs/domain/open-questions.md))·상품군·번들·등급·주문 견적·검색 색인/재색인/캐시.
- 독립 공개 접근 C의 독립 플래그·관리 API.
- 이미 시작한 전송 종료·다운로드 파일 회수·URL 영구 폐기.
- 공개 시점 빌드 객체 존재 재확인.
