# 작업: Order

## 완료 (MVP·검증 기록 있음)

- [x] 단일 상품 주문 생성, Catalog 내부 근거 조회·기대 버전/금액 비교, 스냅샷(정가·할인 0·청구액) 고정과 `OrderPlaced` Outbox 원자 저장 — 2026-10-16
- [x] `(buyerSubject, requestId)` 유일 제약·동일 요청 재조회·동시 생성 한 건 확정 — 2026-10-16
- [x] 내 주문 목록/상세 페이지, JWT·MEMBER·본인 조회·타인 404·소유자/상태 위조 무효 — 2026-10-16
- [x] 결제 결과 반영: `OrderPaymentCompleted`/`Failed` 연결·금액 검증·행 잠금·Inbox·최종 상태, 재전달 무효과 — 2026-10-16
- [x] 구 견적·항목·Redis·ShedLock·만료 Sweeper·취소 API 제거, V10 신규 테이블·구 자료 보존 — 2026-10-16
- [x] 중복 구매 제한: 같은 상품 `PENDING`→409 `PURCHASE_IN_PROGRESS`·`PAID`→409 `PRODUCT_ALREADY_PURCHASED`·최종 `FAILED`만 새 주문, V11 유일 제약 — 2026-10-18
- [x] 최초 `PAID`에 `OrderPaid` Outbox 기록(License 연계), 재전달·FAILED 비발행 — 2026-10-17
- [x] 결제 생명주기 기반: `PAYMENT_PROCESSING`/`EXPIRED` 추가, `createdAt+30분` 고정 기한, 승인 진행 확정 내부 API·불변 영수증·같은 주문 잠금·만료 거절 커밋, `expiresAt` 공개 — 2026-10-29, `docs/order-payment-lifecycle-plan.md`
- [x] `OrderExpired` 발행: 자동 스캔·기한 후 접수 거절·실패 종료의 원자 발행과 Payment 전달, 역순·중복 처리 — 2026-10-29, `docs/order-expiry-propagation-plan.md`
- [x] 전액 환불 접수/조회, 4시간 자동 허용 자격 보존, ADMIN 목록/상세/판정(먼저 커밋 확정), 승인→지급 확인→정지 요청→정지 확인→`RefundExecutionRequested` — `docs/refund-session-handoff.md`
- [x] 환불 금액 상세(amountDetails) A안: 주문·승인 PG 실결제/할인·확인 취소/포인트·미확인 목록, Order V27 — 2026-10-30, `docs/g2-refund-amount-details-implementation.md`
- [x] 공개 상세 B·판매 상태: `publicDetailAllowed` false 신규 주문 404, 관리자 BLOCKED 새 승인 접수 차단, 판매자 STOPPED가 기존 주문·기한 미변경 — 2026-11-02, `docs/public-product-detail-plan.md`·`docs/catalog-sales-policy-plan.md`
- [x] 통합·여정 검증: 실제 MySQL/Kafka·Catalog HTTP·결과 픽스처·OCI/OrbStack 회귀 (최신 판매 관리 게이트 Order 579건 등) — 각 완료 기록

## 진행 중 / 남음 (일반화 공백)

- [ ] G1 결제 생명주기 종료 조건: 금융 보류 건의 다음 행동과 실제 TEST 채널 전체 여정의 종결 기준 — G1
- [ ] G2 지급 확인 누락의 Order 측 근거 기반 복구 연계 (License P3 A 완료, Order 연계는 미결) — G2
- [ ] 환불 후 재구매(P4) 정책: 허용/불허와 새 구매·권한 연결·과거 이벤트 격리. 현재 차단 구현을 최종 불허로 확정하지 않음 — G2 후속
- [ ] 반려 후 재심사의 Order 측 계약 정립·검증 (관련 API는 구현됨, 전체 여정·정책 확정은 남음) — G2 후속

## 범위 밖(후속)

- 장바구니·복수 상품·수량 선택·선물·쿠폰·할인 조합·다중 통화·무료 상품 지급.
- 부분 환불·다중 PG·조사 종결/담당 배정·추가 알림.
- 주문 전용 지표·전용 운영 화면·성능/처리량 고도화.
