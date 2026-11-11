# 작업: License

> 상태: 작성 중(결정 150·178). 형식: 무엇이 · 어디서(`파일:줄`) · 목표 · 영향 · 재현 테스트. 경로 줄임 `license:` = `apps/license/src/main/java/com/stove/license/`, `license/db/Vn` = Flyway 이행 파일. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준. 모듈을 넘는 항목은 [플랫폼 tasks](../platform/tasks.md)(T 번호)에 있다.

## 완료 (검증 결과는 근거 색인)

- [x] `OrderPaid` 수신·검증(mapper + 내부 `PaidOrder`), 소비 그룹 `license-paid-order` 분리 — 2026-10-17
- [x] (V10에서 구매 근거·보유권 분리로 대체) `(buyerSubject, productId)`별 영구 보유권 1개 지급, 권한·Inbox·`LicenseGranted` Outbox 같은 트랜잭션 — 2026-10-17
- [x] 주문별 불변 구매 근거(OrderPaid 13개 필드) 보존, 입력 전체 비교·출처 ID 재사용·상품 ID/코드 충돌 거부 — 2026-10-17
- [x] 같은 입력 재전달 수렴(licenseId·acquiredAt 불변), 다른 구매자 별도 권한, 동시 최초 생성 유일 제약 수렴 — 2026-10-17
- [x] 내 라이브러리 두 GET(MEMBER·JWT sub, 미보유/타인 404·익명 401·역할 없음 403), 페이지 상한·정렬·소유권·공개 필드(최초 5필드, 정지 도입 후 `status` 포함 6필드) — 2026-10-17
- [x] 운영 HTTP 없음, 지급 실패는 공통 재시도·DLT(자동 환불 안 함), 기존 PAID 소급 지급 없음 — 2026-10-17 `docs/security.md`
- [x] V1~V4 자료 보존, V5에서 출처를 권한에 통합(`license_purchase`는 과거 자료로만 보존) — 2026-10-18
- [x] (결정 89로 폐지) 다른 주문의 같은 상품을 충돌로 처리(V5 1:1 모델), Order의 PENDING·PAID 중복 구매 차단과 분담 — 2026-10-18
- [x] 환불용 ACTIVE/SUSPENDED와 정지·복구 이력, `LicenseSuspensionRequested`·`LicenseRestorationRequested` 수신→결과 Outbox — `docs/services.md`
- [x] Order 지급 확인(`LicenseGranted` 소비)과 기존 이벤트 운영 복구(DLT 재투입·Outbox DEAD 재큐잉) — 2026-10-31 P3 A ([결정 37](../../../docs/decisions.md#license-event-recovery), [근거 색인](../../../docs/evidence.md))
- [x] 실제 아홉 앱 격리 스택 여정·전체 회귀 검증 — 2026-10-18 통합 후 13단계·Order/Payment 포함 30단계, 2026-10-31 P3 A 1,951건 ([근거 색인](../../../docs/evidence.md))
- [x] 환불 후 재구매(P4): 구매는 판매 금지만 막고 보유 여부는 등록에서 판단, 보관함(구매 근거 `STORED`)·등록 API, 등록마다 새 보유권 행, 정지 대상 = 구매 근거, 복구 세 갈래(보관함 반환 포함), `PurchaseRefunded`로 환불 완료 종료, 라이브러리·보관함에서 끝난 항목 제외 — [결정 89~98](../../../docs/decisions.md#purchase-blocked-only-by-sale-ban), PR 본문·[근거 색인](../../../docs/evidence.md)
- [x] 보관함·등록을 포함한 실제 스택 여정: `verify-jib.py`(11개 앱) 통과, `verify-license.py` 15단계(환불 완료 뒤 재구매·등록, PG 결과 주입 — 결정 100) 통과 — 2026-11-06 PR #116
- [x] PG 취소 구간까지 거치는 환불 후 재구매 여정: PortOne 흉내 서버로 Payment의 실제 취소 경로 → 환불 완료 → 보유권 REFUNDED·등록 ENDED → 재구매·새 보유권 등록(`verify-refund-settlement.py`) — 2026-11-07 [결정 111](../../../docs/decisions.md#portone-fake-journey), PR #129. 실제 PortOne·PG 확인은 [Payment 작업](../payment/tasks.md)에 남김
- [x] 복구·승인 정정이 모듈 간 시계 차이로 정상 요청을 거부하던 결함 수정(시각 비교 가드·V8/V9 CHECK 제거, V12) — [D-052](../../../docs/defects.md#d-052), PR #183

## 코드와 맞출 것

돈·보유권 영향 순서다.

- [ ] **MC-L1. 복구된 구매 근거에 늦은 취소의 `PurchaseRefunded`가 오면 회수하지 못하고 영구 실패한다** ([결정 157](../../../docs/decisions.md#late-cancellation-confirmation), 1회차 지적 A2·B2, 판정 전)
  - 어디서: `license:core/domain/License.java:121-138`(`refund`: `status == SUSPENDED && 현재 정지 == 요청 정지`만 허용, `:127`), `license:core/service/ApplyPurchaseRefund.java:29-40`(정지 기록·복구 기록을 보지 않음).
  - 목표: R11-4, design 구매 근거 표 E7의 "늦은 취소 가드" — 요청 정지 ID가 이 구매 근거의 `license_suspension`이고 그 정지의 `license_restoration`이 있으면 `STORED`·`REGISTERED`에서도 `REFUNDED`, 열린 보유권(`ACTIVE` 포함) `ENDED`, `LicenseRefundApplied`(LI-T1). 승인 정정으로 끝난 정지·기록 없는 정지는 영구 실패. 그 사이 같은 `refundId`의 재신청 정지가 진행 중이어도 회수하고 현재 정지를 비우며, 회수 뒤 늦게 온 같은 `refundId`의 정지 요청은 Inbox만 쓴다(E2 `REFUNDED` 칸, [결정 179](../../../docs/decisions.md#late-cancellation-during-retry)).
  - 영향: **보유권·돈** — 늦은 취소 반영 뒤 고객이 환불받은 돈과 `ACTIVE` 보유권을 함께 갖는다. `PurchaseRefunded`가 실패 보관·키 정지로 가 같은 주문의 뒤 이벤트도 멈춘다.
  - 재현: 통합 — 정지 → `LicenseRestorationRequested`로 `REGISTERED`·`ACTIVE` 복구 → 같은 정지 ID의 `PurchaseRefunded` 주입 → 지금 `CONFLICT`(Red), 목표 `REFUNDED`·보유권 `ENDED`·Outbox `LicenseRefundApplied` 1건. 보관함 반환 뒤(`STORED`)·재등록 뒤(새 `ACTIVE`) 변형, 승인 정정으로 끝난 정지를 가리키면 거절.

- [ ] **LI-T1. 회수 응답 사실 `LicenseRefundApplied`를 내지 않고, 회수에 쓴 `PurchaseRefunded`를 기록하지 않는다** ([결정 158](../../../docs/decisions.md#forward-gap-counting), 플랫폼 T6, 1회차 지적 B4, 판정 전)
  - 어디서: `license:core/service/ApplyPurchaseRefund.java:29-40`(Outbox 없음, `OutboxRecorder` 주입 없음), `license:config/LicenseSuspensionConfiguration.java:22-25`, `license/db/V10:36-39`(`refunded_suspension_request_event_id`·`refunded_at`만, `PurchaseRefunded.eventId`·`refundId` 칸 없음). `common/kafka/.../contract/EventType.java`에 상수 없음.
  - 목표: R11-1·R11-2, design "발행" `LicenseRefundApplied`(가칭, 제안 본문). `REFUNDED`로 바뀐 트랜잭션에서만 Outbox, 재전달은 내지 않음. 새 열 `refunded_event_id`·`refunded_refund_id` + `uk_product_license_refund_event`(옛 `REFUNDED` 행 채우기 방침과 `migrationTest` 포함 — 옛 행은 `PurchaseRefunded` 식별자를 모름, Order V30 채움 이벤트 포함, 결정 99). Order 소비(`order-license-refund`)는 Order 모듈 스펙. 이름·칸 확정은 플랫폼 이벤트 표(A5).
  - 영향: **보유권(감지)** — License가 `PurchaseRefunded`를 실패 보관에 둔 채 멈춰도 Order가 셀 응답이 없어 환불 완료 주문의 보유권 정지·라이브러리 노출이 조용히 남는다(U3).
  - 재현: `PurchaseStorageIntegrationTest`에 회수 뒤 `outbox_event`의 `LicenseRefundApplied` 1행 단언(지금 Red), 같은 `PurchaseRefunded` 재전달 뒤에도 1행, 같은 정지·다른 `eventId`는 `uk_product_license_refund_event`로 거절.

- [ ] **LI-T2. 정지 전 상태가 `SUSPENDED` + `suspended_from` 보조 칸이고 CHECK가 없다** (소급 RC-17, [결정 193](../../../docs/decisions.md#split-suspended-status))
  - 어디서: `license:core/domain/License.java:64-67`·`:88-89`·`:102`·`:128`, `license:core/domain/PurchaseStatus.java:9-10`, `license:core/domain/LicenseRepository.java:31-34`(보관함 조회가 `suspendedFrom`으로 분기), `license/db/V10:37`·`:41`(열·옮김, 값·조합 CHECK 없음).
  - 목표: R12-2, design "상태 모델". `PurchaseStatus` 값 둘(`SUSPENDED_FROM_STORED`·`SUSPENDED_FROM_REGISTERED`) + `chk_product_license_status` 다섯 값 + `suspended_from` 열 삭제. 복구·회수·`findStorage`를 상태 값으로. `StorageItemResponse.status`는 두 값을 `SUSPENDED`로 매핑. 이행은 Flyway 기준선(T34) 뒤 증분, 옛 행 채움 없음(R9-5).
  - 영향: **보유권** — `SUSPENDED`인데 `suspended_from`이 NULL이거나 잘못된 값인 행(수동 SQL·결함)이 생겨도 DB가 막지 않는다. 그 행의 복구는 `STORED` 갈래로 가서 열린 `SUSPENDED` 보유권이 남고(`uk_license_registration_open`로 재등록도 막힘) 고객은 게임을 못 쓴다.
  - 재현: 통합 — `status='SUSPENDED', suspended_from=NULL` 행 삽입이 지금 성공(Red), 목표에서는 `status='SUSPENDED'` 자체가 CHECK 위반. 상태×트리거 표의 `정지·S`·`정지·R` 열 전이 단위 테스트.

- [ ] **LI-T3. 상태에 딸린 식별자 칸의 조합을 DB가 지키지 않는다**
  - 어디서: `license/db/V5`·`V10:36-39`(`suspension_request_event_id`·`refunded_suspension_request_event_id`·`refunded_at` 모두 NULL 허용, CHECK 없음), `license:core/domain/License.java:90`·`:115-116`·`:133-136`.
  - 목표: R12-3, design "목표에서 더할 제약" `chk_product_license_suspension`·`chk_product_license_refund`.
  - 영향: **보유권** — 정지 상태인데 현재 정지 ID가 없으면 어떤 복구·회수도 맞지 않아 영구 실패로 멈춘다. `REFUNDED`인데 회수 근거가 없으면 재전달 판정이 깨진다.
  - 재현: 이행 테스트 — 각 위반 조합 삽입이 CHECK에 걸리는지.

- [ ] **LI-T4. 구매 근거 생성 사실의 이름이 `LicenseGranted`다** ([결정 175](../../../docs/decisions.md#purchase-record-stored), 플랫폼 T33, 1회차 지적 B9, 판정 전)
  - 어디서: `license:core/service/GrantLicense.java:45-48`(`LicenseGrantedEvent`), `common/kafka/.../contract/EventType.java`(`LICENSE_GRANTED`).
  - 목표: R1-1, design "이름 이행". Order가 두 타입을 받는 판이 먼저 나간 뒤 License가 `PurchaseRecordStored`를 낸다(P2.7-4). 재발행 허용 목록은 이행 동안 두 이름(LI-T8).
  - 영향: 동작은 같다. 새 소비자가 "보유권 활성화"로 오해할 여지를 없앤다.
  - 재현: 계약 테스트 — `GrantLicense`가 `PurchaseRecordStored` 타입·같은 본문·키를 쓰는지, 옛 `LicenseGranted` `SENT` 행 재발행이 이행 중 허용되는지.

- [ ] **LI-T5. License 시계를 한 트랜잭션에서 여러 번 읽고, 일부는 주입한 `Clock` 빈을 쓰지 않는다**
  - 어디서: `license:core/service/ApplyLicenseRestoration.java:59-60`, `license:core/service/ApplyApprovalCorrection.java:55-56`(옛 보유권 `ended_at`과 기록 `restored_at`·결과 `occurredAt`이 다른 `clock.instant()`), `license:core/domain/License.java:168`(`Instant.now()`), `license:core/service/RegisterLicense.java:38`(`Clock.systemUTC()`).
  - 목표: LI-I12, design "설정" `Clock` 빈 하나. 처리마다 한 번 읽은 시각을 상태 변경·기록·결과 이벤트에 같이 쓴다. 시각은 값으로만 쓰므로 판정에는 영향이 없다(원칙 ⑥ 해당 없음).
  - 영향: 돈·보유권 없음. 같은 사건의 시각이 µs 단위로 달라 조사·대조가 헷갈리고, 생성·등록 시각을 테스트에서 고정할 수 없다.
  - 재현: 고정 `Clock` 단위 테스트 — 복구 한 번 뒤 `ended_at == restored_at == LicenseRestored.occurredAt`, `GrantLicense` 결과 `acquiredAt`이 주입 시각과 같은지.

- [ ] **LI-T6. 영구 실패와 재시도 가능 실패가 구분되지 않는다** (플랫폼 T20·T2 의존)
  - 어디서: 모든 서비스가 `BusinessException(CONFLICT|INVALID_REQUEST|NOT_FOUND)`를 던지고 공통 `DefaultErrorHandler`가 모두 4회 재시도한다. `license:api/listener/ApprovalCorrectionEventListener.java`의 토픽 검사는 `IllegalStateException`(재시도 대상)이고 구독 토픽이 하나라 닿지 않는 분기다.
  - 목표: R5-4, design "소비". 구매 근거 없음(`NOT_FOUND`)만 재시도 가능, 형식·식별자 모순·내용 불일치(`INVALID_REQUEST`·`CONFLICT`)는 공통 영구 실패 예외로 바로 실패 보관·키 정지(P2.6-3·4·5). 닿지 않는 토픽 분기는 지운다(여러 토픽 구독 시에만 P2.3-8).
  - 영향: **보유권(간접)** — 영구 실패 한 건이 같은 키(주문)의 뒤 이벤트를 7초 이상 막는다. 키 정지(T2)가 들어오면 분류가 회수 순서를 정한다.
  - 재현: `LicenseIntegrationTest#conflictGoesToDltAndOriginalReplayRecoversInANewTransaction` 변형 — `CONFLICT` 입력의 처리 시도 1회 단언.

- [ ] **LI-T7. 리스너가 `schemaVersion`으로 매퍼를 고르지 않는다** ([결정 170](../../../docs/decisions.md#schema-version-header), 플랫폼 T25 의존)
  - 어디서: `license:api/listener/*EventListener.java`(모두 `isType`만 보고 `payloadAs` 하나).
  - 목표: R8-6. 헤더 없으면 1.
  - 영향: 돈 없음. Order가 받는 이벤트 본문 모양을 바꿀 때 License가 옛·새 버전을 함께 읽지 못한다.
  - 재현: 헤더 `schemaVersion=2` 견본 레코드를 넣었을 때 버전 2 매퍼로 가는지.

- [ ] **LI-T8. 재발행 허용 목록이 코드 상수이고 목표 이벤트가 빠져 있다** ([결정 161](../../../docs/decisions.md#republish-all-current), 플랫폼 T7)
  - 어디서: `common/messaging/.../ops/OutboxRepublishTargets.java:27-37`(License 4종).
  - 목표: R7-11, design "결과 이벤트를 다시 내지 않는 규칙과 재발행". 플랫폼 이벤트 표에서 생성하거나 대조 테스트로 지키고 `LicenseRefundApplied`(LI-T1)·`PurchaseRecordStored`(LI-T4)를 포함.
  - 영향: **보유권(복구)** — `LicenseRefundApplied`가 Kafka에서 사라지면 Order가 회수 누락으로 세도 되살릴 길이 없다.
  - 재현: `LicenseOutboxRepublishIntegrationTest#licenseRepublishesOnlyItsOwnSuspensionRestorationAndCorrectionResults`에 새 타입 추가.

- [ ] **LI-T9. 모듈 안 불변식 일부에 확인 테스트가 없다**
  - 어디서: design "모듈 안 불변식" LI-I4·LI-I5·LI-I6·LI-I7.
  - 목표: LI-I4 — 같은 구매 근거에 열린 보유권 두 번째 삽입이 `uk_license_registration_open`에 걸림. LI-I5 — 상태 조합 점검 쿼리(구매 근거 상태 × 열린 보유권 상태)를 통합 테스트 끝에서 0행 단언. LI-I6 — `ENDED`·`ended_at` NULL 삽입 거절. LI-I7 — 복구된 정지를 가리키는 정정 요청 거절.
  - 영향: 회귀를 막는 테스트 공백. 지금 동작은 맞다.
  - 재현: 위 각 항목이 테스트 자체다.

## 진행 중 / 남음

- [x] 정지 전 상태 표현(소급 RC-17) — [결정 193](../../../docs/decisions.md#split-suspended-status): 상태 값 둘로 나눔 → LI-T2.
- [x] 늦은 취소 회수 때 같은 구매 근거가 재신청 정지로 정지 중인 경우 — [결정 179](../../../docs/decisions.md#late-cancellation-during-retry)로 정함(R11-4, MC-L1).
- [ ] 2회차 독립 평가에서 1회차 지적 A2·B2·B4·B9(+A5)의 판정을 받는다(결정 178). 이 초안은 지적을 고친 것으로 치지 않는다.
- [ ] [외부 대기] 공유 환경 자료 전환: 점검 SQL·미발행 OrderPaid Outbox·Kafka 미소비/실패 보관 대조, 기존 복수 구매 근거 자료의 보존·전환 정책(실제 자료 확인 뒤 결정), 과거 PAID 소급 지급 필요성 판단 — 미수행
- [x] (닫힘) 구성 결함 후보(Kafka 보존 7일이 DLT 재투입 기한) — [결정 160](../../../docs/decisions.md#dead-letter-store)으로 실패 레코드를 소비 모듈 DB 보관 표에 기한 없이 두고, 토픽 선언·자동 생성 끄기는 플랫폼 T11. License 쪽 할 일은 공통 라이브러리 적용뿐(플랫폼 T2·T7)
- [x] (닫힘) 지급 전 정지 요청의 운영 경로(옛 설계 "찾은 불일치 7") — 같은 키 `orderNo`의 `OrderPaid`가 앞에서 실패 보관되면 뒤 `LicenseSuspensionRequested`도 같은 키 정지로 보관돼 원래 순서로 재투입된다([결정 155](../../../docs/decisions.md#key-stop-on-dead)·160). 구매 근거 없음은 재시도 가능 실패(R5-4)
- [ ] License 이벤트 복구 런북(`docs/license-event-recovery-runbook.md`)을 결정 160의 실패 보관 표 재투입·키 단위 회수로 고친다(공통 라이브러리 구현 뒤)

## 범위 밖(후속)

- 선물(보관함 항목의 받는 사람 변경) — 정책 미정, 로드맵 3절 "제품 확장 검토", G2 비대상.
- 고객 알림(보관함 반환·환불 결과) — [결정 98](../../../docs/decisions.md#storage-return-no-notification)로 표시 정책은 정했고, 알림 기능 자체는 결제·심사와 함께 쓰는 공통 기능으로 따로 정한다.
- 기간제·구독·체험판·양도·좌석/기기 권한, 외부 CD키·DRM.
- 이미 받은 파일의 회수.
- DB 유실 뒤 전량 재구축, 성능 실험.
