# 작업: License

## 완료 (MVP·V5 통합·정지/복구·운영 복구 검증 기록 있음)

- [x] `OrderPaid` 수신·검증(mapper + 내부 `PaidOrder`), 소비 그룹 `license-paid-order` 분리 — 2026-10-17 `docs/license-mvp-plan.md`
- [x] `(buyerSubject, productId)`별 영구 보유권 1개 지급, 권한·Inbox·`LicenseGranted` Outbox 같은 트랜잭션 — 2026-10-17
- [x] 주문별 불변 구매 근거(OrderPaid 13개 필드) 보존, 입력 전체 비교·출처 ID 재사용·상품 ID/코드 충돌 거부 — 2026-10-17
- [x] 같은 입력 재전달 수렴(licenseId·acquiredAt 불변), 다른 구매자 별도 권한, 동시 최초 생성 유일 제약 수렴 — 2026-10-17
- [x] 내 라이브러리 두 GET(MEMBER·JWT sub, 미보유/타인 404·익명 401·역할 없음 403), 페이지 상한·정렬·소유권·공개 5필드 — 2026-10-17
- [x] 운영 HTTP 없음, 지급 실패는 공통 재시도·DLT(자동 환불 안 함), 기존 PAID 소급 지급 없음 — 2026-10-17 `docs/security.md`
- [x] V1~V4 자료 보존, V5에서 출처를 권한에 통합(`license_purchase`는 과거 자료로만 보존) — 2026-10-18 `docs/license-mvp-plan.md`
- [x] 다른 주문의 같은 상품을 충돌로 처리(V5 1:1 모델), Order의 PENDING·PAID 중복 구매 차단과 분담 — 2026-10-18
- [x] 환불용 ACTIVE/SUSPENDED와 정지·복구 이력, `LicenseSuspensionRequested`·`LicenseRestorationRequested` 수신→결과 Outbox — `docs/services.md`
- [x] Order 지급 확인(`LicenseGranted` 소비)과 기존 이벤트 운영 복구(DLT 재투입·Outbox DEAD 재큐잉) — 2026-10-31 P3 A `docs/license-event-recovery-verification.md`
- [x] 실제 아홉 앱 격리 스택 여정·전체 회귀 검증 (관련 다수) — 2026-10-17 최초, 2026-10-31 P3 A 1,951건

## 진행 중 / 남음 (일반화 공백)

- [ ] 환불 완료 후 재구매 허용/불허 정책과 새 권한·구매 이력 연결 — G2 후속 P4 (`platform-generalization-plan.md` License 행·G2)
- [ ] 재구매 허용 시 구매 근거/보유권 세대와 과거 이벤트로부터 새 권한을 보호하는 계약 — G2 후속 P4
- [ ] 재구매 불허로 확정될 경우의 일관된 안내·거절과 이력 보존 검증 — G2 후속 P4

## 범위 밖(후속)

- 선물·기간제·구독·체험판·양도·좌석/기기 권한, 외부 CD키·DRM.
- 이미 받은 파일의 회수.
- DB 유실 뒤 전량 재구축, 전용 운영 API·스케줄러·성능 실험.
