# 작업: License

## 완료 (MVP·V5 통합·정지/복구·운영 복구 검증 기록 있음)

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

## 진행 중 / 남음 (일반화 공백)

- [x] 환불 후 재구매(P4): 구매는 판매 금지만 막고 보유 여부는 등록에서 판단, 보관함(구매 근거 `STORED`)·등록 API, 등록마다 새 보유권 행, 정지 대상 = 구매 근거, 복구 세 갈래(보관함 반환 포함), `PurchaseRefunded`로 환불 완료 종료, 라이브러리·보관함에서 끝난 항목 제외 — [결정 89~98](../../../docs/decisions.md#purchase-blocked-only-by-sale-ban), PR 본문·[근거 색인](../../../docs/evidence.md)
- [ ] [결정 필요] 선물(보관함 항목의 받는 사람 변경) — 보관함 구조만 준비, 정책 미정. 로드맵 3절 "제품 확장 검토" 대상이며 G2 종료 조건이 아니다
- [ ] [결정 필요] 고객 알림(보관함 반환·환불 결과) — 알림 수단(메일·앱 푸시)·소유 모듈·구독할 업무 이벤트가 미결이다([결정 98](../../../docs/decisions.md#storage-return-no-notification) "알림 기능 자체의 설계는 미결"). 보관함 반환 표시 정책은 [결정 98](../../../docs/decisions.md#storage-return-no-notification)으로 정했다(보관함 반환은 따로 알리지 않고 환불 상세에 표시). 알림 기능 자체는 결제·심사와 함께 쓰는 공통 기능으로 따로 만들며, G2를 막지 않는다
- [x] 보관함·등록을 포함한 실제 스택 여정: `verify-jib.py`(11개 앱) 통과, `verify-license.py` 15단계(환불 완료 뒤 재구매·등록, PG 결과 주입 — 결정 100) 통과 — 2026-11-06 PR #116
- [x] PG 취소 구간까지 거치는 환불 후 재구매 여정: PortOne 흉내 서버로 Payment의 실제 취소 경로 → 환불 완료 → 보유권 REFUNDED·등록 ENDED → 재구매·새 보유권 등록(`verify-refund-settlement.py`) — 2026-11-07 [결정 111](../../../docs/decisions.md#portone-fake-journey), PR #129. 실제 PortOne·PG 확인은 [Payment 작업](../payment/tasks.md)에 남김
- [ ] [외부 대기] 공유 환경 자료 전환: 점검 SQL·미발행 OrderPaid Outbox·Kafka 미소비/DLT 대조, 기존 복수 구매 근거 자료의 보존·전환 정책(실제 자료 확인 뒤 결정), 과거 PAID 소급 지급 필요성 판단 — 미수행

## 범위 밖(후속)

- 기간제·구독·체험판·양도·좌석/기기 권한, 외부 CD키·DRM.
- 이미 받은 파일의 회수.
- DB 유실 뒤 전량 재구축, 전용 운영 API·스케줄러·성능 실험.
